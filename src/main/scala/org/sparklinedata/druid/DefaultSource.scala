/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sparklinedata.druid

import org.apache.spark.Logging
import org.apache.spark.sql.{SQLContext}
import org.apache.spark.sql.sources.{BaseRelation, RelationProvider}
import org.json4s._
import org.json4s.ext.{EnumNameSerializer}
import org.json4s.jackson.JsonMethods._
import org.sparklinedata.druid.metadata.{DruidRelationInfo, FunctionalDependencyType, FunctionalDependency}

class DefaultSource extends RelationProvider with Logging {

  import DefaultSource._

  override def createRelation(sqlContext: SQLContext,
                              parameters: Map[String, String]): BaseRelation = {

    import Utils.jsonFormat

    val sourceDFName = parameters.getOrElse(SOURCE_DF_PARAM,
      throw new DruidDataSourceException(
        s"'$SOURCE_DF_PARAM' must be specified for Druid DataSource")
    )

    val sourceDF = sqlContext.table(sourceDFName)

    val dsName : String = parameters.getOrElse(DRUID_DS_PARAM,
      throw new DruidDataSourceException(
        s"'$DRUID_DS_PARAM' must be specified for Druid DataSource")
    )

    val timeDimensionCol : String = parameters.getOrElse(TIME_DIMENSION_COLUMN_PARAM,
      throw new DruidDataSourceException(
        s"'$TIME_DIMENSION_COLUMN_PARAM' must be specified for Druid DataSource")
    )

    // validate field is in SourceDF.
    sourceDF.schema(timeDimensionCol)

    val maxCardinality =
      parameters.getOrElse(MAX_CARDINALITY_PARAM, DEFAULT_MAX_CARDINALITY).toLong
    val cardinalityPerDruidQuery =
      parameters.getOrElse(MAX_CARDINALITY_PER_DRUID_QUERY_PARAM,
        DEFAULT_CARDINALITY_PER_DRUID_QUERY).toLong

    val columnMapping : Map[String, String] =
      parameters.get(SOURCE_TO_DRUID_NAME_MAP_PARAM).map { s =>
      val o = parse(s).asInstanceOf[JObject]
      o.obj.map(t => (t._1, t._2.values.toString)).toMap
    }.getOrElse(Map())

    val fds : List[FunctionalDependency] = parameters.get(FUNCTIONAL_DEPENDENCIES_PARAM).map { s=>
      parse(s).extract[List[FunctionalDependency]]
    }.getOrElse(List())

    val druidHost = parameters.get(DRUID_HOST_PARAM).getOrElse(DEFAULT_DRUID_HOST)
    val druidPort : Int = parameters.get(DRUID_PORT_PARAM).getOrElse(DEFAULT_DRUID_PORT).toInt

    val drI = DruidRelationInfo(sourceDFName, sourceDF,
    dsName,
    timeDimensionCol,
    druidHost,
    druidPort,
    columnMapping,
    fds,
    maxCardinality,
    cardinalityPerDruidQuery)

    logInfo(drI.fd.depGraph.debugString(drI.druidDS))

    val dQuery = parameters.get(DRUID_QUERY).map { s =>
      parse(s).extract[DruidQuery]
    }

  new DruidRelation(drI, dQuery)(sqlContext)
  }
}

object DefaultSource {

  val SOURCE_DF_PARAM = "sourceDataframe"

  /**
   * DataSource name in Druid.
   */
  val DRUID_DS_PARAM = "druidDatasource"

  val TIME_DIMENSION_COLUMN_PARAM = "timeDimensionColumn"
  
  /**
   * If the result cardinality of a Query exceeeds this value then Query is not
   * converted to a Druid Query.
   */
  val MAX_CARDINALITY_PARAM = "maxResultCardinality"
  val DEFAULT_MAX_CARDINALITY : String = (1 * 1000 * 1000).toString

  /**
   * If the result size estimate exceeds this number, and attempt is made to run 'n'
   * druid queries, each of which spans a sub interval of the total time interval.
   * 'n' is computed as `result.size % thisParam + 1`
   */
  val MAX_CARDINALITY_PER_DRUID_QUERY_PARAM = "maxCardinalityPerQuery"
  val DEFAULT_CARDINALITY_PER_DRUID_QUERY = (100 * 1000).toString

  /**
   * Map column names to Druid field names.
   * Specified as a json string.
   */
  val SOURCE_TO_DRUID_NAME_MAP_PARAM = "columnMapping"

  /**
   * Specify how columns are related, see
   * [[org.sparklinedata.druid.metadata.FunctionalDependency]]. Specified as a list of
   * functional dependency objects.
   */
  val FUNCTIONAL_DEPENDENCIES_PARAM = "functionalDependencies"

  val DRUID_HOST_PARAM = "druidHost"
  val DEFAULT_DRUID_HOST = "localhost"

  val DRUID_PORT_PARAM = "druidPort"
  val DEFAULT_DRUID_PORT = "8082"  // the broker port

  // this is only for test purposes
  val DRUID_QUERY = "druidQuery"

}
