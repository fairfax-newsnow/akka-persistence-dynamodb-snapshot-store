package akka.persistence.snapshot.dynamodb

import scala.concurrent.Future
import scala.collection.JavaConverters._
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.{SnapshotSelectionCriteria, SelectedSnapshot}
import DynamoDBUtils._
import akka.serialization.SerializationExtension
import com.amazonaws.services.dynamodbv2.model._
import ComparisonOperator._
import com.sclasen.spray.aws.dynamodb.{DynamoDBClient}
import akka.actor.{ActorLogging, ActorRefFactory, ActorSystem}
import java.util.concurrent.TimeUnit
import com.amazonaws.AmazonServiceException
import java.nio.ByteBuffer
import java.util.{Map => JMap}
import akka.persistence.serialization.Snapshot
import akka.persistence.SnapshotMetadata
import com.sclasen.spray.aws.dynamodb.DynamoDBClientProps

class DynamoDBSnapshotStore extends SnapshotStore with ActorLogging {

  import DynamoDBSnapshotStore._

  val config = context.system.settings.config.getConfig("dynamodb-snapshot-store")
  val table = config.getString("snapshot-table")
  val snapshotName = config.getString("snapshot-name")
  val serialization = SerializationExtension(context.system)
  val client = dynamoClient(context.system, context)
  val queryLimit = config.getInt("query-limit")

  implicit val dispatcher = context.system.dispatchers.lookup(config.getString("stream-dispatcher"))

  def loadAsync(persistenceId: String, criteria: SnapshotSelectionCriteria): Future[Option[SelectedSnapshot]] = {
    def loadLatestAsync(seqNrs: Seq[Long]): Future[Option[SelectedSnapshot]] = seqNrs match {
      case x +: xs =>
        val key = getUniqueKey(persistenceId)(x)

        println(s"loadLatestAsync, key = $key")

        getItem {
          new GetItemRequest()
            .withTableName(table)
            .withKey(key)
            .withAttributesToGet(List(Key, SequenceNr, Timestamp, Payload).asJava)
        }.map {
          r =>
            val attrs = r.getItem
            val md = SnapshotMetadata(attrs.get(Key).getS, attrs.get(SequenceNr).getN.toLong, attrs.get(Timestamp).getN.toLong)
            val Snapshot(state) = deserialize(attrs.get(Payload).getB)
            println(s"state = $state")
            Some(SelectedSnapshot(md, state))
        } recoverWith {
          case e =>
            println(s"got error, $e, try the next latest snapsot")
            loadLatestAsync(xs)
        }
      case _ => Future.successful(None)
    }

    println(s"!!!DynamoDBSnapshotStore.loadAsync($persistenceId, $criteria)")

    querySeqNrs(persistenceId)(criteria.maxSequenceNr).flatMap {
      seqNrs =>
        val descNrs = seqNrs.sortWith(_ > _)
        println(s"!!descNrs = $descNrs")
        loadLatestAsync(descNrs)
    }
  }

  def saveAsync(metadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    println(s"!!!DynamoDBSnapshotStore.saveAsync($metadata, $snapshot)")
    val req = new PutItemRequest().withTableName(table) withItem toSnapshotItem(metadata, snapshot)
    putItem(req).map {
      _ =>
        log.debug(s"at=put-item-finish, finish writing snapshot of ${req.getItem.get(Key)} to table ${req.getTableName}")
    }
  }

  def saved(metadata: SnapshotMetadata) = {
    println(s"!!!DynamoDBSnapshotStore.saved($metadata)")
  }

  def delete(md: SnapshotMetadata) = {
    println(s"!!!DynamoDBSnapshotStore.delete($md)")
    delete1Snapshot(md.persistenceId)(md.sequenceNr)
  }

  def delete(persistenceId: String, criteria: SnapshotSelectionCriteria) = {
    println(s"!!!DynamoDBSnapshotStore.delete($persistenceId, $criteria)")
    querySeqNrs(persistenceId)(criteria.maxSequenceNr).map {
      _.foreach(delete1Snapshot(persistenceId))
    }
  }

  private def delete1Snapshot(persistenceId: String)(seqNr: Long) = {
    deleteItem {
      new DeleteItemRequest()
        .withTableName(table)
        .withKey {
        getUniqueKey(persistenceId)(seqNr)
      }
    }.map {
      _ => println(s"!!!snapshot of persistenceId $persistenceId sequenceNr $seqNr deleted")
    }
  }

  private def querySeqNrs(persistenceId: String)(maxSeqNr: Long): Future[Seq[Long]] = {
    def createReq(startKey: JMap[String, AttributeValue]): QueryRequest =
      new QueryRequest().withTableName(table)
        .withKeyConditions {
        Map(
          Key -> keyCondition(EQ)(_.withS(persistenceId)),
          SequenceNr -> keyCondition(LE)(_.withN(maxSeqNr.toString))
        ).asJava
      }.withAttributesToGet(List(SequenceNr).asJava)
        .withLimit(queryLimit).withExclusiveStartKey(startKey)

    def processQueryResult(seqNrs: Seq[Long])(r: QueryResult): Future[Seq[Long]] = {
      val newSeqNrs = r.getItems.asScala.toList.map(getSeqNr) ++: seqNrs
      val evalKey = r.getLastEvaluatedKey
      println(
        s"""
             |processQueryResult,
             |newSeqNrs = $newSeqNrs
             |getLastEvaluatedKey = $evalKey
           """.stripMargin)
      if (evalKey == null)
        Future(newSeqNrs)
      else
        queryItem {
          createReq(evalKey)
        }.flatMap {
          processQueryResult(newSeqNrs)
        }
    }

    queryItem {
      createReq(null)
    }.flatMap {
      processQueryResult(Seq.empty[Long])
    }
  }

  private def toSnapshotItem(metadata: SnapshotMetadata, snapshot: Any): Item = fields(
    Key -> S(metadata.persistenceId),
    SequenceNr -> N(metadata.sequenceNr),
    Timestamp -> N(metadata.timestamp),
    Payload -> serialize(Snapshot(snapshot))
  )

  private def serialize(snapshot: Snapshot): AttributeValue =
    B(serialization.findSerializerFor(snapshot).toBinary(snapshot))

  private def deserialize(bytes: ByteBuffer): Snapshot =
    serialization.deserialize(getArray(bytes), classOf[Snapshot]).get

  private def dynamoClient(system: ActorSystem, context: ActorRefFactory): DynamoDBClient = {
    val props = DynamoDBClientProps(
      config.getString("aws-access-key-id"),
      config.getString("aws-secret-access-key"),
      config.getDuration("operation-timeout", TimeUnit.MILLISECONDS),
      system,
      context,
      config.getString("endpoint")
    )
    new InstrumentedDynamoDBClient(props)
  }

  private def queryItem(r: QueryRequest): Future[QueryResult] = withBackoff(r)(client.query)

  private def putItem(r: PutItemRequest): Future[PutItemResult] = withBackoff(r)(client.putItem)

  private def getItem(r: GetItemRequest): Future[GetItemResult] = withBackoff(r)(client.getItem)

  private def deleteItem(r: DeleteItemRequest): Future[DeleteItemResult] = withBackoff(r)(client.deleteItem)

  private def withBackoff[I, O](i: I, retriesRemaining: Int = 10)(op: I => Future[Either[AmazonServiceException, O]]): Future[O] =
    op(i).flatMap {
      case Left(t: ProvisionedThroughputExceededException) =>
        backoff(10 - retriesRemaining, i.getClass.getSimpleName)
        withBackoff(i, retriesRemaining - 1)(op)
      case Left(e) =>
        log.error(e, "exception in withBackoff")
        throw e
      case Right(resp) =>
        Future.successful(resp)
    }


  def backoff(retries: Int, what: String): Unit =
    if (retries == 0) Thread.`yield`()
    else {
      val sleep = math.pow(2, retries).toLong
      log.warning("at=backoff request={} sleep={}", what, sleep)
      Thread.sleep(sleep)
    }

}

object DynamoDBSnapshotStore {
  val Payload = "snapshot"
  val Timestamp = "timestamp"
  val Key = "key"
  val SequenceNr = "sequenceNr"

  import collection.JavaConverters._

  def getUniqueKey(persistenceId: String)(seqNr: Long): JMap[String, AttributeValue] =
    Map(
      Key -> new AttributeValue().withS(persistenceId),
      SequenceNr -> new AttributeValue().withN(seqNr.toString)
    ).asJava

  def getSeqNr(attrs: JMap[String, AttributeValue]): Long =
    attrs.get(SequenceNr).getN.toLong

  def keyCondition(comparison: ComparisonOperator)(attrVal: AttributeValue => AttributeValue): Condition =
    new Condition().withComparisonOperator(comparison.toString).withAttributeValueList(attrVal(new AttributeValue()))

  val schema = Seq(
    new KeySchemaElement().withKeyType(KeyType.HASH).withAttributeName(Key),
    new KeySchemaElement().withKeyType(KeyType.RANGE).withAttributeName(SequenceNr)
  ).asJava

  val schemaAttributes = Seq(
    new AttributeDefinition().withAttributeName(Key).withAttributeType("S"),
    new AttributeDefinition().withAttributeName(SequenceNr).withAttributeType("N")
  ).asJava
}

class InstrumentedDynamoDBClient(props: DynamoDBClientProps) extends DynamoDBClient(props) {
  def logging[T](op: String)(f: Future[Either[AmazonServiceException, T]]): Future[Either[AmazonServiceException, T]] = {
    f.onFailure {
      case e: Exception => props.system.log.error(e, "error in async op {}", op)
    }
    f
  }

  override def putItem(aws: PutItemRequest): Future[Either[AmazonServiceException, PutItemResult]] =
    logging("putItem")(super.putItem(aws))
}