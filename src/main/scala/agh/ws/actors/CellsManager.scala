package agh.ws.actors

import agh.ws._
import agh.ws.actors.Cell.{Iterate, IterationCompleted, NeighbourRegistered, Position, RegisterNeighbour}
import agh.ws.messagess._
import agh.ws.util.{BoundiresBehavior, Boundries, CellsCounter, Direction}
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer

import scala.concurrent.duration._
import scala.collection.mutable

object CellsManager{
  final case class RegisterCell(position: Position) extends Request
  final case class CellRegistered(requestId: Long, actorRef: ActorRef, position: Position) extends Response

  def props(boundries: Boundries, boundiresBehavior: BoundiresBehavior, cellsOffset:Float): Props = Props(new CellsManager(boundries, boundiresBehavior, cellsOffset))

  val matchPrecision = 0.01
}

class CellsManager(boundires: Boundries, boundiresBehavior: BoundiresBehavior, cellsOffset:Float) extends Actor with ActorLogging {

  import CellsManager._

  private val cellsOrderedByPosition: mutable.Map[Position, ActorRef] = scala.collection.mutable.SortedMap[Position, ActorRef]()(Ordering.by(p => (p.y, p.x)))
  implicit val system: ActorSystem = context.system
  implicit val materializer: ActorMaterializer = ActorMaterializer()


  override def receive: Receive = waitingForMessagess(
    Map.empty
  )

  def waitingForMessagess(implicit pending: Map[Long, ActorRef]): Receive = {
    case req @ RegisterCell(position) =>
      log.debug(s"Cell reqister request #${req.requestId} - $position")
      val newCell = context.actorOf(Cell.props(position), s"cell-${position.x}-${position.y}-${CellsCounter.inc}")
      context.watch(newCell)
      cellsOrderedByPosition += (position -> newCell)
      sender() ! CellRegistered(req.requestId, newCell, position)
      registerInNeighbourhood(position, newCell)

    case response @ NeighbourRegistered(_) =>
      log.debug(s"Registered cell neighbour of ${sender()}")
      receivedResponse(pending, response)

    case req @ Iterate() =>
      val requester = sender()
      context.actorOf(CellsQuery.props(
        cellsOrderedByPosition.values.toSet,
        req.requestId,
        requester,
        Iterate(),
        10.seconds
      ))
      pendingRequest(pending, requester, req)

    case res @ QueryResponse(_, _: Map[ActorRef, IterationCompleted]) =>
      log.info("Finished iteration")
      receivedResponse(pending, res)

    case msg @ _ => log.warning(s"MANAGER - Unknown message $msg from ${sender()}")


  }


  def pendingRequest(pending: Map[Long, ActorRef], senderRef: ActorRef, request: Request): Unit = {
    context become waitingForMessagess(pending + (request.requestId -> senderRef))
  }

  def pendingRequest(pending: Map[Long, ActorRef], senderRef: ActorRef, requests: Iterable[Request]): Unit = {
    val newRequests = requests
      .map(req => req.requestId -> senderRef)
      .toMap
    context become waitingForMessagess(pending ++ newRequests)
  }

  def receivedResponse(pending: Map[Long, ActorRef], response: Response): Unit = {
    val ref = pending.getOrElse(response.requestId, self)
    if(ref!=self)
      ref ! response
    context become waitingForMessagess(pending - response.requestId)
  }

  private def registerInNeighbourhood(position: Position, newCell: ActorRef)(implicit pendingRequests: Map[Long, ActorRef]): Unit = {
    val requests = findCellNeighbours(position)
      .map(ref => {
        val request = RegisterNeighbour(newCell)
        ref ! request
        request
      })
    pendingRequest(pendingRequests, self, requests)
  }

  private def findCellNeighbours(position: Position): Seq[ActorRef] = {
    Direction.directions
      .flatMap(direction => boundiresBehavior.neighbourPosition(position, direction, boundires, cellsOffset))
      .flatMap(findCell(cellsOrderedByPosition.toStream, _))
  }

  @scala.annotation.tailrec
  private def findCell(seq: Stream[(Position, ActorRef)], target: Position): Option[ActorRef] = seq match {
    case (that, ref) #:: _ if util.Math.square(that.x - target.x) <= matchPrecision && util.Math.square(that.y - target.y) <= matchPrecision => Some(ref)
    case Stream.Empty => None
    case _ #:: xs => findCell(xs, target)
  }

}
