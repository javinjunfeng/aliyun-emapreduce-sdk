/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aliyun.emr.examples.maxcompute

import org.apache.spark.SparkConf
import org.apache.spark.sql.{SaveMode, SparkSession}
import java.sql.Date
/**
 * Created by songjun on 16/12/30.
 */
object TestODPSDataSource {
  def main(args: Array[String]): Unit = {
    if (args.length < 6) {
      System.err.println(
        """Usage: TestOdps <accessKeyId> <accessKeySecret> <envType> <project> <table> <numPartitions>
          |
          |Arguments:
          |
          |    accessKeyId      Aliyun Access Key ID.
          |    accessKeySecret  Aliyun Key Secret.
          |    envType          0 or 1
          |                     0: Public environment.
          |                     1: Aliyun internal environment, i.e. Aliyun ECS etc.
          |    project          Aliyun ODPS project
          |    table            Aliyun ODPS table
          |    numPartitions    the number of RDD partitions
        """.stripMargin)
      System.exit(1)
    }

    val accessKeyId = args(0)
    val accessKeySecret = args(1)
    val envType = args(2).toInt
    val project = args(3)
    val table = args(4)
    val numPartitions = args(5).toInt

    val urls = Seq(
      Seq("http://service.odps.aliyun.com/api", "http://dt.odps.aliyun.com"), // public environment
      Seq("http://odps-ext.aliyun-inc.com/api", "http://dt-ext.odps.aliyun-inc.com") // Aliyun internal environment
    )

    val conf = new SparkConf().setAppName("Test Odps Read").setMaster("local")
    val ss = SparkSession.builder().appName("Test Odps Read").master("local").getOrCreate()

    import ss.implicits._
    val odpsDF =  ss.read
      .format("org.apache.spark.aliyun.maxcompute.datasource")
      .option("odpsUrl", "http://service.odps.aliyun.com/api")
      .option("tunnelUrl", "http://dt.odps.aliyun.com")
      .option("table", table)
      .option("project", project)
      .option("accessKeySecret", accessKeySecret)
      .option("accessKeyId", accessKeyId).load()

    odpsDF.select("a","day").show

    val xxdf = ss.sparkContext.makeRDD((23 to 29).map(a => (Date.valueOf("2016-08-01"),a.toString)).toSeq).toDF("day","a")

    xxdf.write.format("org.apache.spark.aliyun.maxcompute.datasource")
      .option("odpsUrl", "http://service.odps.aliyun.com/api")
      .option("tunnelUrl", "http://dt.odps.aliyun.com")
      .option("table", table)
      .option("project", project)
      .option("accessKeySecret", accessKeySecret)
      .option("accessKeyId", accessKeyId).option("partitionSpec", "b='haha'").mode(SaveMode.Append).save()

  }
}
