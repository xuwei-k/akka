/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.journal.leveldb

import akka.actor.ActorRef
import akka.annotation.InternalApi
import akka.persistence.JournalProtocol.{ RecoverySuccess, ReplayMessages, ReplayMessagesFailure, ReplayedMessage }
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.LeveldbJournal
import akka.persistence.journal.leveldb.LeveldbJournal.EventAppended
import akka.persistence.query.{ EventEnvelope, Sequence }
import akka.persistence.query.journal.leveldb.EventsByPersistenceIdStage.Continue
import akka.stream.{ ActorMaterializer, Attributes, Outlet, SourceShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler, TimerGraphStageLogicWithLogging }

import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object EventsByPersistenceIdStage {
  case object Continue
}

/**
 * INTERNAL API
 */
@InternalApi
final private[akka] class EventsByPersistenceIdStage(
    persistenceId: String,
    fromSequenceNr: Long,
    initialToSequenceNr: Long,
    maxBufSize: Int,
    writeJournalPluginId: String,
    refreshInterval: Option[FiniteDuration],
    mat: ActorMaterializer)
    extends GraphStage[SourceShape[EventEnvelope]] {
  val out: Outlet[EventEnvelope] = Outlet("EventsByPersistenceIdSource")
  override def shape: SourceShape[EventEnvelope] = SourceShape(out)
  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    val logic = new TimerGraphStageLogicWithLogging(shape) with OutHandler with Buffer[EventEnvelope] {
      val journal: ActorRef = Persistence(mat.system).journalFor(writeJournalPluginId)
      var currSeqNo = fromSequenceNr
      var stageActorRef: ActorRef = null
      var replayInProgress = false
      var outstandingReplay = false

      var nextSequenceNr = fromSequenceNr
      var toSequenceNr = initialToSequenceNr

      override def preStart(): Unit = {
        stageActorRef = getStageActor(journalInteraction).ref
        refreshInterval.foreach(fd => {
          scheduleWithFixedDelay(Continue, fd, fd)
          journal.tell(LeveldbJournal.SubscribePersistenceId(persistenceId), stageActorRef)
        })
        requestMore()
      }

      private def requestMore(): Unit = {
        if (!replayInProgress) {
          val limit = maxBufSize - bufferSize
          if (limit > 0 && nextSequenceNr <= toSequenceNr) {
            replayInProgress = true
            outstandingReplay = false
            val request = ReplayMessages(nextSequenceNr, toSequenceNr, limit, persistenceId, stageActorRef)
            journal ! request
          }
        } else {
          outstandingReplay = true
        }
      }

      override protected def onTimer(timerKey: Any): Unit = {
        requestMore()
        deliverBuf(out)
        maybeCompleteStage()
      }

      private def journalInteraction(in: (ActorRef, Any)): Unit = {
        val (_, msg) = in
        msg match {
          case ReplayedMessage(pr) =>
            buffer(
              EventEnvelope(
                offset = Sequence(pr.sequenceNr),
                persistenceId = pr.persistenceId,
                sequenceNr = pr.sequenceNr,
                event = pr.payload))
            nextSequenceNr = pr.sequenceNr + 1
            deliverBuf(out)

          case RecoverySuccess(highestSeqNr) =>
            replayInProgress = false
            deliverBuf(out)

            if (highestSeqNr < toSequenceNr && isCurrentQuery()) {
              toSequenceNr = highestSeqNr
            }
            log.debug(
              "Replay complete. From sequenceNr {} currentSequenceNr {} toSequenceNr {} buffer size {}",
              fromSequenceNr,
              nextSequenceNr,
              toSequenceNr,
              bufferSize)
            if (bufferEmpty && (nextSequenceNr > toSequenceNr || nextSequenceNr == fromSequenceNr)) {
              completeStage()
            } else if (nextSequenceNr < toSequenceNr) {
              // need further requests to the journal
              if (bufferSize < maxBufSize && (isCurrentQuery() || outstandingReplay)) {
                requestMore()
              }
            }

          case ReplayMessagesFailure(cause) =>
            failStage(cause)

          case EventAppended(_) =>
            requestMore()
        }
      }

      private def isCurrentQuery(): Boolean = refreshInterval.isEmpty

      private def maybeCompleteStage(): Unit = {
        if (bufferEmpty && nextSequenceNr > toSequenceNr) {
          completeStage()
        }
      }

      override def onPull(): Unit = {
        requestMore()
        deliverBuf(out)
        maybeCompleteStage()

      }

      setHandler(out, this)
    }
    logic
  }

}
