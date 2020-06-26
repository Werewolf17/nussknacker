package pl.touk.nussknacker.engine.avro.schemaregistry.confluent.serialization

import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import org.apache.avro.Schema
import org.apache.kafka.common.errors.SerializationException
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.ConfluentUtils
import pl.touk.nussknacker.engine.avro.schemaregistry.confluent.client.ConfluentSchemaRegistryClient

class ConfluentKafkaAvroSerializationMixin {
  def fetchSchema[T](confluentSchemaRegistryClient: ConfluentSchemaRegistryClient, topic: String, version: Option[Int], isKey: Boolean): Schema =
    confluentSchemaRegistryClient
      .getFreshSchema(topic, version, isKey)
      .valueOr(exc => throw new SerializationException(s"Error retrieving Avro schema for topic $topic.", exc))

  def fetchSchemaId(confluentSchemaRegistryClient: ConfluentSchemaRegistryClient, topic: String, schema: Schema, isKey: Boolean): Int =
    try {
      val subject = ConfluentUtils.topicSubject(topic, isKey = isKey)
      confluentSchemaRegistryClient.client.getId(subject, schema)
    } catch {
      case exc: RestClientException =>
        throw new SerializationException(s"Error retrieving Avro schema id for topic: $topic and schema: $schema.", exc)
    }
}