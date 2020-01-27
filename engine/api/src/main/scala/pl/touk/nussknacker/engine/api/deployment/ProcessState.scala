package pl.touk.nussknacker.engine.api.deployment
import java.net.URI

import io.circe.generic.JsonCodec
import io.circe._
import pl.touk.nussknacker.engine.api.ProcessVersion
import pl.touk.nussknacker.engine.api.deployment.ProcessActionType.ProcessActionType
import pl.touk.nussknacker.engine.api.deployment.ProcessState.StateStatusCodec

trait ProcessStateDefinitionManager {
  def statusActions(stateStatus: StateStatus): List[ProcessActionType]
  def statusTooltip(stateStatus: StateStatus): Option[String]
  def statusIcon(stateStatus: StateStatus): Option[URI]
  def mapActionToStatus(stateAction: Option[ProcessActionType]): StateStatus
}

object ProcessState {
  implicit val uriEncoder: Encoder[URI] = Encoder.encodeString.contramap(_.toString)
  implicit val uriDecoder: Decoder[URI] = Decoder.decodeString.map(URI.create)

  def apply(deploymentId: String, status: StateStatus, version: Option[ProcessVersion], allowedActions: List[ProcessActionType]): ProcessState =
    ProcessState(DeploymentId(deploymentId), status, version, allowedActions)

  def apply(deploymentId: DeploymentId,
            status: StateStatus,
            version: Option[ProcessVersion],
            definitionManager: ProcessStateDefinitionManager,
            startTime: Option[Long],
            attributes: Option[Json],
            errorMessage: Option[String]): ProcessState =
    ProcessState(
      deploymentId,
      status,
      version,
      definitionManager.statusActions(status),
      definitionManager.statusIcon(status),
      definitionManager.statusTooltip(status),
      startTime,
      attributes,
      errorMessage
    )

  @JsonCodec case class StateStatusCodec(clazz: String, value: String)
}

@JsonCodec case class ProcessState(deploymentId: DeploymentId,
                                   status: StateStatus,
                                   processVersionId: Option[ProcessVersion] = Option.empty,
                                   allowedActions: List[ProcessActionType] = List.empty,
                                   icon: Option[URI] = Option.empty,
                                   tooltip: Option[String] = Option.empty,
                                   startTime: Option[Long] = Option.empty,
                                   attributes: Option[Json] = Option.empty,
                                   errorMessage: Option[String] = Option.empty) {
  def isDeployed: Boolean = status.isRunning || status.isDuringDeploy
}

object ProcessActionType extends Enumeration {
  implicit val typeEncoder: Encoder[ProcessActionType.Value] = Encoder.enumEncoder(ProcessActionType)
  implicit val typeDecoder: Decoder[ProcessActionType.Value] = Decoder.enumDecoder(ProcessActionType)

  type ProcessActionType = Value
  val Deploy: Value = Value("DEPLOY")
  val Cancel: Value = Value("CANCEL")
  val Pause: Value = Value("PAUSE") //TODO: To implement in future..
}

sealed trait StateStatus {
  def isDuringDeploy: Boolean = false
  def isFinished: Boolean = false
  def isRunning: Boolean = false
  def canDeploy: Boolean = false
  def name: String
}

object StateStatus {
  import io.circe.syntax._

  implicit val statusEncoder: Encoder[StateStatus] = Encoder.encodeJson.contramap(st => StateStatusCodec(st.getClass.getSimpleName, st.name).asJson)
  implicit val statusDecoder: Decoder[StateStatus] = (c: HCursor) => cursorToFailureOrStatus(c)

  //This field keeps all available statuses. Remember!! If you want add new status class you have to add it also here!
  val availableStatusClasses: Map[String, Class[_ <: StateStatus]] = List(
    classOf[NotEstablishedStateStatus],
    classOf[StoppedStateStatus],
    classOf[DuringDeployStateStatus],
    classOf[FinishedStateStatus],
    classOf[RunningStateStatus]
  )
    .map(clz => clz.getSimpleName -> clz)
    .toMap[String, Class[_ <: StateStatus]]

  private def cursorToFailureOrStatus(cursor: HCursor): Either[DecodingFailure, StateStatus] = {
    cursor.as[StateStatusCodec] match {
      case Left(failure) => Left(failure)
      case Right(codec) => availableStatusClasses
        .get(codec.clazz)
        .map(clazz => {
          try {
            Right(clazz.getConstructor(classOf[String]).newInstance(codec.value))
          } catch {
            case exc: Exception => Left(DecodingFailure(s"Failed to decode StateStatus. Error: ${exc.getMessage}", cursor.history))
          }
        })
        .getOrElse(Left(DecodingFailure(s"Failed to decode StateStatus. Error: class $codec doesn't exist.", cursor.history)))
    }
  }
}

trait StateStatusFollowingDeployAction {
  def isFollowingDeployAction(stateStatus: StateStatus): Boolean =
    stateStatus.isDuringDeploy || stateStatus.isRunning || stateStatus.isFinished
}

final class NotEstablishedStateStatus(val name: String) extends StateStatus

final class StoppedStateStatus(val name: String) extends StateStatus {
  override def canDeploy: Boolean = true
}

final class DuringDeployStateStatus(val name: String) extends StateStatus {
  override def isDuringDeploy: Boolean = true
}

final class FinishedStateStatus(val name: String) extends StateStatus {
  override def isFinished: Boolean = true
  override def canDeploy: Boolean = true
}

final class RunningStateStatus(val name: String) extends StateStatus {
  override def isRunning: Boolean = true
}
