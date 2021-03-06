package com.twitter.finagle.http2.transport

import com.twitter.finagle.{FailureFlags, Stack}
import com.twitter.finagle.http2.{RefTransport, Http2Transporter}
import com.twitter.finagle.transport.{Transport, TransportProxy}
import com.twitter.util.{Promise, Future, Time, Return, Throw}
import io.netty.handler.codec.http.HttpClientUpgradeHandler.UpgradeEvent

/**
 * This transport waits for a message that the upgrade has either succeeded or
 * failed when it reads.  Once it learns of one of the two, it changes `ref` to
 * respect that upgrade.  Since `ref` starts out pointing to
 * `Http2UpgradingTransport`, once it updates `ref`, it knows it will no longer
 * take calls to write or read.
 */
private[http2] class Http2UpgradingTransport(
    t: Transport[Any, Any],
    ref: RefTransport[Any, Any],
    p: Promise[Option[MultiplexedTransporter]],
    params: Stack.Params)
  extends TransportProxy[Any, Any](t) {

  import Http2Transporter._

  def write(any: Any): Future[Unit] = t.write(any)
  def read(): Future[Any] = t.read().flatMap {
    case _@UpgradeEvent.UPGRADE_REJECTED =>
      synchronized {
        p.updateIfEmpty(Return(None))
        // we need ref to update before we can read again
        ref.update(identity)
        ref.read()
      }
    case _@UpgradeEvent.UPGRADE_SUCCESSFUL =>
      synchronized {
        val casted =
          Transport.cast[Http2ClientDowngrader.StreamMessage, Http2ClientDowngrader.StreamMessage](t)
        val multiplexed = new MultiplexedTransporter(casted, t.remoteAddress, params)
        p.updateIfEmpty(Return(Some(multiplexed)))
        ref.update { _ =>
          unsafeCast(multiplexed.first())
        }
        ref.read()
      }
    case result =>
      Future.value(result)
  }

  override def close(deadline: Time): Future[Unit] = synchronized {
    p.updateIfEmpty(Throw(new Http2UpgradingTransport.ClosedWhileUpgradingException()))
    super.close(deadline)
  }
}

private object Http2UpgradingTransport {
  class ClosedWhileUpgradingException(
      private[finagle] val flags: Long = FailureFlags.Empty)
    extends Exception("h2c transport was closed while upgrading")
    with FailureFlags[ClosedWhileUpgradingException] {

    protected def copyWithFlags(newFlags: Long): ClosedWhileUpgradingException =
      new ClosedWhileUpgradingException(newFlags)
  }
}
