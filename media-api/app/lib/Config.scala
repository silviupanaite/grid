package lib

import java.io.{FileInputStream, File}
import scala.collection.JavaConverters._
import scala.io.Source.fromFile
import scala.util.Random

import play.api.{Logger, Play}

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.auth.{BasicAWSCredentials, AWSCredentials}
import com.amazonaws.services.ec2.model.{InstanceStateName, Filter, DescribeInstancesRequest}
import com.gu.mediaservice.lib.config.PropertiesConfig


object Config {

  val appConfig = Play.current.configuration

  def apply(key: String): String =
    string(key)

  def string(key: String): String =
    appConfig.getString(key) getOrElse missing(key, "string")

  def int(key: String): Int =
    appConfig.getInt(key) getOrElse missing(key, "integer")

  val stage: String = stageFromFile getOrElse "DEV"

  val elasticsearchHost: String =
    if (stage == "DEV") string("es.host") else ec2ElasticsearchHost

  private lazy val properties: Map[String, String] =
    PropertiesConfig.fromFile("/etc/gu/media-api.conf")

  private lazy val awsCredentials: AWSCredentials =
    new BasicAWSCredentials(properties("aws.id"), properties("aws.secret"))

  private lazy val ec2Client: AmazonEC2Client = {
    val client = new AmazonEC2Client(awsCredentials)
    client.setEndpoint("ec2.eu-west-1.amazonaws.com")
    client
  }

  @annotation.tailrec
  private def ec2ElasticsearchHost: String = {
    val elasticsearchRole = "media-service-elasticsearch"
    val instances = ec2Client.describeInstances(new DescribeInstancesRequest().withFilters(
      new Filter("instance-state-name", List(InstanceStateName.Running.toString).asJava),
      new Filter("tag:Stage", List(stage).asJava),
      new Filter("tag:Role", List(elasticsearchRole).asJava)
    ))

    val hosts = instances.getReservations.asScala
      .flatMap(_.getInstances.asScala)
      .map(_.getPublicDnsName)
    Logger.info(s"Available Elasticsearch hosts in EC2: [${hosts.mkString(", ")}]")

    Random.shuffle(hosts).headOption match {
      case None =>
        Logger.warn("Could not find an Elasticsearch host. Trying again...")
        Thread.sleep(1000)
        ec2ElasticsearchHost
      case Some(host) =>
        Logger.info(s"Using Elasticsearch host $host")
        host
    }
  }

  private def stageFromFile: Option[String] = {
    val file = new File("/etc/gu/stage")
    if (file.exists) Some(fromFile(file).mkString.trim) else None
  }

  private def missing(key: String, type_ : String): Nothing =
    sys.error(s"Required $type_ configuration property missing: $key")

}
