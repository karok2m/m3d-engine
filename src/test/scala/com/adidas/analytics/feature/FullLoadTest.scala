package com.adidas.analytics.feature

import com.adidas.analytics.algo.FullLoad
import com.adidas.analytics.util.{DFSWrapper, HiveTableAttributeReader, LoadMode}
import com.adidas.utils.TestUtils._
import com.adidas.utils.{BaseAlgorithmTest, FileReader, Table}
import org.apache.hadoop.fs.Path
import org.apache.spark.sql.types.{DataType, StructType}
import org.scalatest.FeatureSpec
import org.scalatest.Matchers._

class FullLoadTest extends FeatureSpec with BaseAlgorithmTest {

  private val sourceEnvironmentLocation: String = "test_landing"
  private val targetDatabase: String = "test_lake"
  private val tableName: String = "test_table"

  private val paramsFileName: String = "params.json"
  private val paramsFileHdfsPath: Path = new Path(hdfsRootTestPath, paramsFileName)

  private val sourceDirPath: Path = new Path(hdfsRootTestPath, s"$sourceEnvironmentLocation/test/$tableName/data")
  private val targetDirPath: Path = new Path(hdfsRootTestPath, s"$targetDatabase/test/$tableName/data")
  private val backupDirPath: Path = new Path(hdfsRootTestPath, s"$targetDatabase/test/$tableName/data_backup")

  feature("Reader mode can be specified in configuration") {
    scenario("when reader_mode is invalid string an exception is thrown") {
      val resourceDir = "failfast_option"
      copyResourceFileToHdfs(s"$resourceDir/params_invalid_reader_mode.json", paramsFileHdfsPath)

      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createNonPartitionedTargetTable(targetSchema)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareSourceData(Seq(s"$resourceDir/new_data_wrong.psv"))

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19

      val caught = intercept[RuntimeException] {
        FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()
      }
      caught.getMessage shouldBe "Invalid reader mode: invalid_mode provided"
    }

    scenario("when reader mode is FailFast and malformed records are present, an exception is thrown") {
      val resourceDir = "failfast_option"
      copyResourceFileToHdfs(s"$resourceDir/params.json", paramsFileHdfsPath)

      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createNonPartitionedTargetTable(targetSchema)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareSourceData(Seq(s"$resourceDir/new_data_wrong.psv"))

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19

      val caught = intercept[RuntimeException] {
        FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()
      }
      caught.getMessage shouldBe "Unable to process data"
    }

    scenario("when reader mode is FailFast and no malformed records are present, load is completed correctly") {
      val resourceDir = "failfast_option"
      copyResourceFileToHdfs(s"$resourceDir/params.json", paramsFileHdfsPath)

      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createNonPartitionedTargetTable(targetSchema)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareDefaultSourceData()

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19

      FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_post.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString
    }

    scenario("when reader mode is DROPMALFORMED and malformed records are present, some records are not loaded") {
      val resourceDir = "failfast_option"
      copyResourceFileToHdfs(s"$resourceDir/params_dropmalformed_mode.json", paramsFileHdfsPath)

      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createNonPartitionedTargetTable(targetSchema)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareSourceData(Seq(s"$resourceDir/new_data_wrong.psv"))

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19

      FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_post.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      assert(actualDf.count() < expectedDf.count())

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString
    }

    scenario("when reader mode is PERMISSIVE and malformed records are present, malformed records are also loaded") {
      val resourceDir = "failfast_option"
      copyResourceFileToHdfs(s"$resourceDir/params_permissive_mode.json", paramsFileHdfsPath)

      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createNonPartitionedTargetTable(targetSchema)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareSourceData(Seq(s"$resourceDir/new_data_wrong.psv"))

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19

      FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_post.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe true
      actualDf.count() shouldBe expectedDf.count()

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString
    }
  }

  feature("Data can be loaded from source to target with full mode") {
    scenario("Loading data to non-partitioned table") {
      val resourceDir = "non_partitioned"
      copyResourceFileToHdfs(s"$resourceDir/$paramsFileName", paramsFileHdfsPath)

      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createNonPartitionedTargetTable(targetSchema)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareDefaultSourceData()

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19

      FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_post.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString

      //check backUp dir is empty
      fs.listStatus(backupDirPath).length shouldBe 0
  }

    scenario("Loading data to partitioned table") {
      val resourceDir = "partitioned"
      copyResourceFileToHdfs(s"$resourceDir/$paramsFileName", paramsFileHdfsPath)

      val targetPath20180110 = new Path(targetDirPath, "year=2018/month=1/day=10")
      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createPartitionedTargetTable(Seq("year", "month", "day"), targetSchema, tableName)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareDefaultSourceData()

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19
      fs.exists(targetPath20180110) shouldBe false

      // executing load
      FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_post.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      fs.exists(targetPath20180110) shouldBe true

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString

      //check backUp dir is empty
      fs.listStatus(backupDirPath).length shouldBe 0
    }

    scenario("Loading data to partitioned table in weekly mode") {
      val resourceDir = "partitioned_weekly"
      copyResourceFileToHdfs(s"$resourceDir/$paramsFileName", paramsFileHdfsPath)

      val targetPath201801 = new Path(targetDirPath, "year=2018/week=1")
      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createPartitionedTargetTable(Seq("year", "week"), targetSchema, tableName)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareDefaultSourceData("landing/new_data_weekly.psv")

      // checking pre-conditions
      spark.read.csv(sourceDirPath.toString).count() shouldBe 25
      targetTable.read().count() shouldBe 19

      fs.exists(targetPath201801) shouldBe false

      // executing load
      FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_post.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      fs.exists(targetPath201801) shouldBe true

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString

      //check backUp dir is empty
      fs.listStatus(backupDirPath).length shouldBe 0
    }

    scenario("Try loading data from location that does not exist and expect the data to be as it was before load") {
      val resourceDir = "partitioned"
      copyResourceFileToHdfs(s"partitioned_not_exist_dir/$paramsFileName", paramsFileHdfsPath)

      val targetPath20180110 = new Path(targetDirPath, "year=2018/month=1/day=10")
      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createPartitionedTargetTable(Seq("year", "month", "day"), targetSchema, tableName)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)

      targetTable.read().count() shouldBe 19

      // executing load
      val caught = intercept[RuntimeException]{
        FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()
      }

      assert(caught.getMessage.equals("Unable to read input location."))

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_pre.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      fs.exists(targetPath20180110) shouldBe false

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString

      //check backUp dir is empty
      fs.listStatus(backupDirPath).length shouldBe 0
    }

    scenario("Try loading data while generating error in backup table creation") {
      val resourceDir = "partitioned"
      copyResourceFileToHdfs(s"$resourceDir/$paramsFileName", paramsFileHdfsPath)

      val targetPath20180110 = new Path(targetDirPath, "year=2018/month=1/day=10")
      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createPartitionedTargetTable(Seq("year", "month", "day"), targetSchema, tableName)
      createPartitionedTargetTable(Seq("year", "month", "day"), targetSchema, tableName + "_temp")
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)

      targetTable.read().count() shouldBe 19

      // executing load
      val caught = intercept[RuntimeException]{
        FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()
      }

      assert(caught.getMessage.equals("Unable to change table location."))

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_pre.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      fs.exists(targetPath20180110) shouldBe false

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString

      //check backUp dir is empty
      fs.listStatus(backupDirPath).length shouldBe 0
    }

    scenario("Try loading data while partitioning column is missing") {
      val resourceDir = "partitioned"
      copyResourceFileToHdfs(s"partitioned_partition_column_wrong/$paramsFileName", paramsFileHdfsPath)

      val targetPath20180110 = new Path(targetDirPath, "year=2018/month=1/day=10")
      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createPartitionedTargetTable(Seq("year", "month", "day"), targetSchema, tableName)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareDefaultSourceData()

      // checking pre-conditions
      targetTable.read().count() shouldBe 19
      fs.exists(targetPath20180110) shouldBe false

      // executing load
      val caught = intercept[RuntimeException]{
        FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()
      }

      assert(caught.getMessage.equals("Unable to transform data frames."))

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_pre.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      fs.exists(targetPath20180110) shouldBe false

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString

      //check backUp dir is empty
      fs.listStatus(backupDirPath).length shouldBe 0
    }

    scenario("Try loading data while date format is wrong") {
      val resourceDir = "partitioned_date_format_wrong"
      copyResourceFileToHdfs(s"$resourceDir/$paramsFileName", paramsFileHdfsPath)

      val targetPath99999999 = new Path(targetDirPath, "year=9999/month=99/day=99")
      val targetSchema = DataType.fromJson(getResourceAsText(s"$resourceDir/target_schema.json")).asInstanceOf[StructType]
      val dataReader = FileReader.newDSVFileReader(Some(targetSchema))

      val targetTable = createPartitionedTargetTable(Seq("year", "month", "day"), targetSchema, tableName)
      setupInitialState(targetTable, s"$resourceDir/lake_data_pre.psv", dataReader)
      prepareDefaultSourceData()

      // checking pre-conditions
      targetTable.read().count() shouldBe 19
      fs.exists(targetPath99999999) shouldBe false

      // executing load
      FullLoad(spark, dfs, paramsFileHdfsPath.toString).run()

      // validating result
      val expectedDataLocation = resolveResource(s"$resourceDir/lake_data_post.psv", withProtocol = true)
      val expectedDf = dataReader.read(spark, expectedDataLocation)
      val actualDf = targetTable.read()
      actualDf.hasDiff(expectedDf) shouldBe false

      fs.exists(targetPath99999999) shouldBe true

      // check the resulting table location is /data folder
      val tableLocation = HiveTableAttributeReader(targetTable.table, spark).getTableLocation
      tableLocation shouldBe fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString

      //check backUp dir is empty
      fs.listStatus(backupDirPath).length shouldBe 0
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    spark.sql(s"DROP DATABASE IF EXISTS $targetDatabase CASCADE")
    spark.sql(s"CREATE DATABASE $targetDatabase")
    logger.info(s"Creating ${sourceDirPath.toString}")
    fs.mkdirs(sourceDirPath)
    logger.info(s"Creating ${targetDirPath.toString}")
    fs.mkdirs(targetDirPath)
  }

  private def createPartitionedTargetTable(targetPartitions: Seq[String], targetSchema: StructType, tableName: String): Table = {
    val targetTableLocation = fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString
    Table.newBuilder(tableName, targetDatabase, targetTableLocation, targetSchema)
      .withPartitions(targetPartitions)
      .buildParquetTable(DFSWrapper(fs.getConf), spark, external = true)
  }

  private def createNonPartitionedTargetTable(targetSchema: StructType): Table = {
    val targetTableLocation = fs.makeQualified(new Path(hdfsRootTestPath, targetDirPath)).toString
    Table.newBuilder(tableName, targetDatabase, targetTableLocation, targetSchema)
      .buildParquetTable(DFSWrapper(fs.getConf), spark, external = true)
  }

  private def prepareDefaultSourceData(sourceData: String = "landing/new_data.psv"): Unit = {
    prepareSourceData(Seq(sourceData))
  }

  private def prepareSourceData(sourceFiles: Seq[String]): Unit = {
    sourceFiles.foreach { file =>
      logger.info(s"copyResourceFileToHdfs $file to ${sourceDirPath.toString}")
      copyResourceFileToHdfs(s"$file", sourceDirPath)
    }
  }

  private def setupInitialState(targetTable: Table, localDataFile: String, dataReader: FileReader): Unit = {
    val initialDataLocation = resolveResource(localDataFile, withProtocol = true)
    targetTable.write(Seq(initialDataLocation), dataReader, LoadMode.OverwritePartitionsWithAddedColumns)
  }
}
