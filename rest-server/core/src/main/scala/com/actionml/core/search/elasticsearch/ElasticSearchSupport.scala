/*
 * Copyright ActionML, LLC under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * ActionML licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.actionml.core.search.elasticsearch

import java.time.Instant

import com.actionml.core.search._
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.LazyLogging
import org.apache.http.HttpHost
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.entity.{ContentType, StringEntity}
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder
import org.apache.http.nio.entity.NStringEntity
import org.apache.http.util.EntityUtils
import org.apache.spark.rdd.RDD
import org.elasticsearch.client.RestClient
import org.elasticsearch.client.RestClientBuilder.HttpClientConfigCallback
import org.json4s.DefaultReaders._
import org.json4s.jackson.JsonMethods._
import org.json4s.{DefaultFormats, JValue, _}

import scala.collection.JavaConverters._
import scala.reflect.ManifestFactory

trait ElasticSearchSupport extends SearchSupport[Hit] {
  override def createSearchClient(aliasName: String): SearchClient[Hit] = ElasticSearchClient(aliasName)
}

trait JsonSearchResultTransformation[T] {
  implicit def reader: Reader[T]
  implicit def manifest: Manifest[T]
  def transform(j: JValue): Seq[T]
}

trait ElasticSearchResultTransformation extends JsonSearchResultTransformation[Hit] {
  override implicit val manifest: Manifest[Hit] = ManifestFactory.classType(classOf[Hit])
  override implicit val reader: Reader[Hit] =  new Reader[Hit] {
    def read(value: JValue): Hit = value match {
      case JObject(fields) if fields.exists(_._1 == "_id") && fields.exists(_._1 == "_score") =>
        Hit(fields.find(_._1 == "_id").get._2.as[String], fields.find(_._1 == "_score").get._2.as[Float])
      case x =>
        throw new MappingException("Can't convert %s to Hit." format x)
    }
  }
  override def transform(j: JValue): Seq[Hit] = {
    (j \ "hits" \ "hits").as[Seq[Hit]]
  }
}

class ElasticSearchClient[T] private (alias: String) extends SearchClient[T] with LazyLogging with WriteToEsSupport {
  this: JsonSearchResultTransformation[T] =>
  import ElasticSearchClient._
  implicit val _ = DefaultFormats

  override def close: Unit = client.close()

  override def createIndex(
    indexType: String,
    fieldNames: List[String],
    typeMappings: Map[String, (String, Boolean)],
    refresh: Boolean): Boolean = {
    val indexName = createIndexName(alias)
    createIndexByName(indexName, indexType, fieldNames, typeMappings, refresh)
  }

  override def deleteIndex(refresh: Boolean): Boolean = {
    client.performRequest(
      // Does the alias exist?
      "HEAD",
      s"/_alias/$alias",
      Map.empty[String, String].asJava)
      .getStatusLine
      .getStatusCode match {
          case 200 =>
            val aliasResponse = client.performRequest(
              "GET",
              s"/_alias/$alias",
              Map.empty[String, String].asJava)
            val responseJValue = parse(EntityUtils.toString(aliasResponse.getEntity))
            val indexSet = responseJValue.extract[Map[String, JValue]].keys
            indexSet.forall(deleteIndexByName(_, refresh))
          case _ => false
        }
  }

  override def search(query: SearchQuery): Seq[T] = {
    var actualIndexName: String = createIndexName(alias)
    client.performRequest(
      // Does the alias exist?
      "HEAD",
      s"/_alias/$alias",
      Map.empty[String, String].asJava)
      .getStatusLine
      .getStatusCode match {
          case 200 =>
            val aliasResponse = client.performRequest(
              "GET",
              s"/_alias/$alias",
              Map.empty[String, String].asJava)
            val responseJValue = parse(EntityUtils.toString(aliasResponse.getEntity))
            val indexSet = responseJValue.extract[Map[String, JValue]].keys
            actualIndexName = indexSet.headOption.getOrElse(createIndexName(alias))
          case _ =>
        }
    logger.info(s"Query:\n$query")
    val response = client.performRequest(
      "POST",
      s"/$actualIndexName/_search",
      Map.empty[String, String].asJava,
      new StringEntity(mkElasticQueryString(query), ContentType.APPLICATION_JSON))
    response.getStatusLine.getStatusCode match {
      case 200 =>
        logger.info(s"Got source from query: $query")
        transform(parse(EntityUtils.toString(response.getEntity)))
      case _ =>
        logger.info(s"Query: $query\nproduced status code: ${response.getStatusLine.getStatusCode}")
        Seq.empty
    }
  }

  override def hotSwap(
    typeName: String,
    indexRDD: RDD[Map[String, Any]],
    fieldNames: List[String],
    typeMappings: Map[String, (String, Boolean)],
    numESWriteConnections: Option[Int] = None): Unit = {
    import org.elasticsearch.spark._
    val newIndex = createIndexName(alias)

    logger.info(s"Create new index: $newIndex, $typeName, $fieldNames, $typeMappings")
    // todo: this should have a typeMappings that is Map[String, (String, Boolean)] with the Boolean saying to use norms
    // taken out for now since there is no client.admin in the REST client. Have to construct REST call directly
    createIndexByName(newIndex, typeName, fieldNames, typeMappings, refresh = false, doNotLinkAlias = true)

    // throttle writing to the max bulk-write connections, which is one per ES core.
    // todo: can we find this from the cluster itself?
    val repartitionedIndexRDD = if (numESWriteConnections.nonEmpty && indexRDD.context.defaultParallelism >
      numESWriteConnections.get) {
      logger.info(s"defaultParallelism: ${indexRDD.context.defaultParallelism}")
      logger.info(s"Coalesce to: ${numESWriteConnections.get} to reduce number of ES connections for saveToEs")
      indexRDD.coalesce(numESWriteConnections.get)
    } else {
      logger.info(s"Number of ES connections for saveToEs: ${indexRDD.context.defaultParallelism}")
      indexRDD
    }

    val newIndexURI = "/" + newIndex + "/" + typeName
    val esConfig = Map("es.mapping.id" -> "id")
    repartitionedIndexRDD.saveToEs(newIndexURI, esConfig)

    val (oldIndexSet, deleteOldIndexQuery) = client.performRequest(
      // Does the alias exist?
      "HEAD",
      s"/_alias/$alias",
      Map.empty[String, String].asJava).getStatusLine.getStatusCode match {
        case 200 => {
          val response = client.performRequest(
            "GET",
            s"/_alias/$alias",
            Map.empty[String, String].asJava)
          val responseJValue = parse(EntityUtils.toString(response.getEntity))
          val oldIndexSet = responseJValue.extract[Map[String, JValue]].keys
          val oldIndexName = oldIndexSet.head
          client.performRequest(
            // Does the old index exist?
            "HEAD",
            s"/$oldIndexName",
            Map.empty[String, String].asJava).getStatusLine.getStatusCode match {
              case 200 => {
                val deleteOldIndexQuery = s""",{ "remove_index": { "index": "${oldIndexName}"}}"""
                (oldIndexSet, deleteOldIndexQuery)
              }
              case _ => (Set(), "")
            }
        }
        case _ => (Set(), "")
      }

    val aliasQuery =
      s"""
        |{
        |    "actions" : [
        |        { "add":  { "index": "${newIndex}", "alias": "${alias}" } }
        |        ${deleteOldIndexQuery}
        |    ]
        |}
      """.stripMargin.replace("\n", "")

    val entity = new NStringEntity(aliasQuery, ContentType.APPLICATION_JSON)
    client.performRequest(
      "POST",
      "/_aliases",
      Map.empty[String, String].asJava,
      entity)
    oldIndexSet.foreach(deleteIndexByName(_, refresh = false))
  }


  private def createIndexByName(
    indexName: String,
    indexType: String,
    fieldNames: List[String],
    typeMappings: Map[String, (String, Boolean)],
    refresh: Boolean,
    doNotLinkAlias: Boolean = false): Boolean = {
      client.performRequest(
        "HEAD",
        s"/$indexName",
        Map.empty[String, String].asJava).getStatusLine.getStatusCode match {
        case 404 => { // should always be a unique index name so fail unless we get a 404
          var mappings =
            s"""
               |"mappings": {
               |  "$indexType": {
               |    "properties": {
            """.stripMargin.replace("\n", "")

          def mappingsField(`type`: String) = {
            s"""
               |    : {
               |      "type": "${`type`}"
               |    },
            """.stripMargin.replace("\n", "")
          }

        val mappingsTail =
        // unused mapping forces the last element to have no comma, fuck JSON
          s"""
             |    "last": {
             |      "type": "keyword"
             |    }
             |}}}
            """.stripMargin.replace("\n", "")

        fieldNames.foreach { fieldName =>
          if (typeMappings.contains(fieldName))
            mappings +=
              (s""""$fieldName"""" + mappingsField(typeMappings(
                fieldName)._1))
          else // unspecified fields are treated as not_analyzed strings
            mappings += (s""""$fieldName"""" + mappingsField(
              "keyword"))
        }

        mappings += mappingsTail
        val aliases = if (doNotLinkAlias) "" else s""","aliases":{"$alias":{}}"""
        // "id" string is not_analyzed and does not use norms
        // val entity = new NStringEntity(mappings, ContentType.APPLICATION_JSON)
        //logger.info(s"Create index with:\n$indexName\n$mappings\n")
        val entity = new NStringEntity(s"{$mappings$aliases}", ContentType.APPLICATION_JSON)

        client.
          performRequest(
            "PUT",
            s"/$indexName",
            Map.empty[String, String].asJava,
            entity).getStatusLine.
          getStatusCode match {
            case 200 =>
              // now refresh to get it 'committed'
              // todo: should do this after the new index is created so no index downtime
              if (refresh) refreshIndexByName(indexName)
            case _ =>
              logger.info(s"Index $indexName wasn't created, but may have quietly failed.")
          }
        true
      }
      case 200 =>
        logger.warn(s"Elasticsearch index: $indexName wasn't created because it already exists. " +
          s"This may be an error. Leaving the old index active.")
        false
      case _ =>
        throw new IllegalStateException(s"/$indexName is invalid.")
        false
    }
  }

  private def deleteIndexByName(indexName: String, refresh: Boolean): Boolean = {
    client.performRequest(
      "HEAD",
      s"/$indexName",
      Map.empty[String, String].asJava).getStatusLine.getStatusCode match {
      case 404 => false
      case 200 =>
        client.performRequest(
          "DELETE",
          s"/$indexName",
          Map.empty[String, String].asJava).getStatusLine.getStatusCode match {
          case 200 =>
            if (refresh) refreshIndexByName(indexName)
          case _ =>
            logger.info(s"Index $indexName wasn't deleted, but may have quietly failed.")
        }
        true
      case _ =>
        throw new IllegalStateException()
        false
    }
  }

  private def refreshIndexByName(indexName: String): Unit = {
    client.performRequest(
      "POST",
      s"/$indexName/_refresh",
      Map.empty[String, String].asJava)
  }
}


object ElasticSearchClient extends LazyLogging {

  def apply(aliasName: String): ElasticSearchClient[Hit] = new ElasticSearchClient[Hit](aliasName) with ElasticSearchResultTransformation

  private def createIndexName(alias: String) = alias + "_" + Instant.now().toEpochMilli.toString

  private val config = ConfigFactory.load() // todo: use ficus or something

  private val client: RestClient = {
    val builder = RestClient.builder(HttpHost.create(config.getString("elasticsearch.uri")))
    if (config.hasPath("elasticsearch.auth")) {
      val authConfig = config.getConfig("elasticsearch.auth")
      builder.setHttpClientConfigCallback(new BasicAuthProvider(
        authConfig.getString("username"),
        authConfig.getString("password")
      ))
    }
    builder.build
  }

  private def mkElasticQueryString(query: SearchQuery): String = {
    import org.json4s.JsonDSL._
    import org.json4s.jackson.JsonMethods._
    def matcherToJson(clauses: Map[String, Seq[Matcher]]) = clauses.map { case (clause, matchers) =>
      matchers.map { m =>
        clause ->
          (m.name -> m.values) ~
          m.boost.fold(JObject())("boost" -> _)
      }
    }.flatten
    val json =
      if (query.should.isEmpty && query.must.isEmpty && query.mustNot.isEmpty)
        JObject()
      else {
        ("size" -> query.size) ~
          ("from" -> query.from) ~
          ("query" ->
            ("bool" ->
              ("should" -> matcherToJson(query.should)) ~
              ("must" -> matcherToJson(query.must)) ~
              ("must_not" -> matcherToJson(query.mustNot))
            )
          ) ~
          ("sort" -> Seq(
            "_score" -> JObject("order" -> JString("desc")),
            query.sortBy -> (("unmapped_type" -> "double") ~ ("order" -> "desc"))
          ))
      }
    logger.info(s"Query to search engine:\n${pretty(json)}")
    compact(render(json))
  }

  private class BasicAuthProvider(username: String, password: String) extends HttpClientConfigCallback {
    private val credentialsProvider = new BasicCredentialsProvider()
    credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password))

    override def customizeHttpClient(httpClientBuilder: HttpAsyncClientBuilder): HttpAsyncClientBuilder = {
      httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
    }
  }
}
