package pl.touk.esp.ui.process.migrate
import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FlatSpec, Matchers}
import pl.touk.esp.engine.canonize.ProcessCanonizer
import pl.touk.esp.engine.standalone.http.Argonaut62Support
import pl.touk.esp.ui.api.ProcessTestData
import pl.touk.esp.ui.db.entity.ProcessEntity.ProcessingType
import pl.touk.esp.ui.process.ProcessToSave
import pl.touk.esp.ui.process.marshall.ProcessConverter
import pl.touk.esp.ui.security.LoggedUser
import pl.touk.esp.ui.validation.ValidationResults.{NodeValidationError, ValidationErrors, ValidationResult}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class StandardProcessMigratorSpec extends FlatSpec with Matchers with ScalaFutures with Argonaut62Support {

  implicit val system = ActorSystem("esp-ui")

  implicit val user = LoggedUser("test", "", List(), List())

  import pl.touk.esp.ui.codec.UiCodecs.validationResultEncode

  trait MockMigrator extends StandardProcessMigrator {

    override def environmentId = "testEnv"

    override def targetEnvironmentId = "targetTestEnv"

    override implicit val materializer = ActorMaterializer()
  }

  import argonaut.ArgonautShapeless._
  import pl.touk.esp.ui.codec.UiCodecs._

  val validProcess = ProcessConverter.toDisplayable(ProcessCanonizer.canonize(ProcessTestData.validProcess), ProcessingType.Streaming)

  it should "not migrate not validating process" in {

    val migrator = new MockMigrator {
      override protected def request(path: String, method: HttpMethod, request: MessageEntity) : Future[HttpResponse] = {
        if (path.startsWith("processValidation") && method == HttpMethods.POST) {
          Marshal(ValidationResult.errors(Map("n1" -> List(NodeValidationError("bad", "message", "", None, false))), List(), List())).to[RequestEntity].map { entity =>
            HttpResponse(StatusCodes.OK, entity = entity)
          }
        } else {
          throw new AssertionError(s"Not expected $path")
        }
      }
    }

    whenReady(migrator.migrate(validProcess)) { result =>
      result shouldBe 'left
      result.left.get shouldBe MigratorValidationError(ValidationErrors(Map("n1" -> List(NodeValidationError("bad","message","" ,None,false))),List(),List()))
      result.left.get.getMessage shouldBe "Cannot migrate, following errors occured: n1 - message"
    }

  }

  it should "migrate valid process" in {

    var migrated : Option[Future[ProcessToSave]] = None

    val migrator = new MockMigrator {
      override protected def request(path: String, method: HttpMethod, request: MessageEntity) : Future[HttpResponse] = {
        if (path.startsWith("processValidation") && method == HttpMethods.POST) {
          Marshal(ValidationResult.errors(Map(), List(), List())).to[RequestEntity].map { entity =>
            HttpResponse(StatusCodes.OK, entity = entity)
          }
        } else if (path.startsWith(s"processes/${validProcess.id}") && method == HttpMethods.PUT) {
          migrated = Some(Unmarshal(request).to[ProcessToSave])
          Marshal(ValidationResult.errors(Map(), List(), List())).to[RequestEntity].map { entity =>
            HttpResponse(StatusCodes.OK, entity = entity)
          }
        } else {
          throw new AssertionError(s"Not expected $path")
        }
      }
    }

    whenReady(migrator.migrate(validProcess)) { result =>
      result shouldBe 'right
    }

    migrated shouldBe 'defined

    whenReady(migrated.get) { processToSave =>
      processToSave.comment shouldBe "Process migrated from testEnv by test"
      processToSave.process shouldBe validProcess

    }

  }



}