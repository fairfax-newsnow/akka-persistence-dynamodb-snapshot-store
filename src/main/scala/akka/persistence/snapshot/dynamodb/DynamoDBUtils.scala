package akka.persistence.snapshot.dynamodb

import com.amazonaws.services.dynamodbv2.model.AttributeValue
import java.nio.ByteBuffer
import java.util.{Map => JMap, HashMap => JHMap, Arrays}

object DynamoDBUtils {
  val Key = "key"

  type Item = JMap[String, AttributeValue]

  def S(value: String): AttributeValue = new AttributeValue().withS(value)

  def N(value: Long): AttributeValue = new AttributeValue().withN(value.toString)

  def B(value: Array[Byte]): AttributeValue = new AttributeValue().withB(ByteBuffer.wrap(value))

//  def messageKey(label: String, persistenceId: String, sequenceNr: Long) = S(List(label, persistenceId, sequenceNr).mkString("-"))

  def fields[T](fs: (String, T)*): JMap[String, T] = {
    val map = new JHMap[String, T]()
    fs foreach {
      case (k, v) => map.put(k, v)
    }
    map
  }

  /*
   * a piece of code copy from com.datastax.driver.core.utils.Bytes.getArray
   */
  def getArray(bytes: ByteBuffer): Array[Byte] = {
    val length = bytes.remaining
    if (bytes.hasArray) {
      val boff = bytes.arrayOffset + bytes.position
      if (boff == 0 && length == bytes.array.length)
        bytes.array
      else
        Arrays.copyOfRange(bytes.array, boff, boff + length)
    } else {
      val array = new Array[Byte](length)
      bytes.duplicate.get(array)
      array
    }
  }

}
