package zio.nio.channels

import java.io.IOException
import java.lang.{ Integer => JInteger, Long => JLong, Void => JVoid }
import java.nio.channels.{
  AsynchronousByteChannel => JAsynchronousByteChannel,
  AsynchronousServerSocketChannel => JAsynchronousServerSocketChannel,
  AsynchronousSocketChannel => JAsynchronousSocketChannel
}
import java.nio.{ ByteBuffer => JByteBuffer }
import java.util.concurrent.TimeUnit

import zio.duration._
import zio.nio.core.{ Buffer, SocketAddress, SocketOption }
import zio.nio.core.channels.AsynchronousChannelGroup
import zio.{ Chunk, IO, Managed, UIO, ZIO }
import zio.interop.javaz._

class AsynchronousByteChannel(private val channel: JAsynchronousByteChannel) {

  /**
   *  Reads data from this channel into buffer, returning the number of bytes
   *  read, or -1 if no bytes were read.
   */
  final private[nio] def readBuffer(b: Buffer[Byte]): IO[Exception, Int] =
    withCompletionHandler[JInteger](h => channel.read(b.buffer.asInstanceOf[JByteBuffer], (), h))
      .map(_.toInt)
      .refineToOrDie[Exception]

  final def read(capacity: Int): IO[Exception, Chunk[Byte]] =
    for {
      b <- Buffer.byte(capacity)
      l <- readBuffer(b)
      a <- b.array
      r <- if (l == -1) {
            ZIO.fail(new IOException("Connection reset by peer"))
          } else {
            ZIO.succeed(Chunk.fromArray(a).take(l))
          }
    } yield r

  /**
   *  Writes data into this channel from buffer, returning the number of bytes written.
   */
  final private[nio] def writeBuffer(b: Buffer[Byte]): IO[Exception, Int] =
    withCompletionHandler[JInteger](h => channel.write(b.buffer.asInstanceOf[JByteBuffer], (), h))
      .map(_.toInt)
      .refineToOrDie[Exception]

  final def write(chunk: Chunk[Byte]): IO[Exception, Int] =
    for {
      b <- Buffer.byte(chunk)
      r <- writeBuffer(b)
    } yield r

  /**
   * Closes this channel.
   */
  final private[channels] val close: IO[Exception, Unit] =
    IO.effect(channel.close()).refineToOrDie[Exception]

  /**
   * Tells whether or not this channel is open.
   */
  final val isOpen: UIO[Boolean] =
    IO.effectTotal(channel.isOpen)
}

class AsynchronousServerSocketChannel(private val channel: JAsynchronousServerSocketChannel) {

  /**
   * Binds the channel's socket to a local address and configures the socket
   * to listen for connections.
   */
  final def bind(address: SocketAddress): IO[Exception, Unit] =
    IO.effect(channel.bind(address.jSocketAddress)).refineToOrDie[Exception].unit

  /**
   * Binds the channel's socket to a local address and configures the socket
   * to listen for connections, up to backlog pending connection.
   */
  final def bind(address: SocketAddress, backlog: Int): IO[Exception, Unit] =
    IO.effect(channel.bind(address.jSocketAddress, backlog)).refineToOrDie[Exception].unit

  final def setOption[T](name: SocketOption[T], value: T): IO[Exception, Unit] =
    IO.effect(channel.setOption(name.jSocketOption, value)).refineToOrDie[Exception].unit

  /**
   * Accepts a connection.
   */
  final val accept: Managed[Exception, AsynchronousSocketChannel] =
    Managed
      .make(
        withCompletionHandler[JAsynchronousSocketChannel](h => channel.accept((), h))
          .map(AsynchronousSocketChannel(_))
      )(_.close.orDie)
      .refineToOrDie[Exception]

  /**
   * The `SocketAddress` that the socket is bound to,
   * or the `SocketAddress` representing the loopback address if
   * denied by the security manager, or `Maybe.empty` if the
   * channel's socket is not bound.
   */
  final def localAddress: IO[Exception, Option[SocketAddress]] =
    IO.effect(
        Option(channel.getLocalAddress).map(new SocketAddress(_))
      )
      .refineToOrDie[Exception]

  /**
   * Closes this channel.
   */
  final private[channels] val close: IO[Exception, Unit] =
    IO.effect(channel.close()).refineToOrDie[Exception]

  /**
   * Tells whether or not this channel is open.
   */
  final val isOpen: UIO[Boolean] =
    IO.effectTotal(channel.isOpen)
}

object AsynchronousServerSocketChannel {

  def apply(): Managed[Exception, AsynchronousServerSocketChannel] = {
    val open = IO
      .effect(JAsynchronousServerSocketChannel.open())
      .refineToOrDie[Exception]
      .map(new AsynchronousServerSocketChannel(_))

    Managed.make(open)(_.close.orDie)
  }

  def apply(
    channelGroup: AsynchronousChannelGroup
  ): Managed[Exception, AsynchronousServerSocketChannel] = {
    val open = IO
      .effect(
        JAsynchronousServerSocketChannel.open(channelGroup.channelGroup)
      )
      .refineOrDie {
        case e: Exception => e
      }
      .map(new AsynchronousServerSocketChannel(_))

    Managed.make(open)(_.close.orDie)
  }
}

class AsynchronousSocketChannel(private val channel: JAsynchronousSocketChannel)
    extends AsynchronousByteChannel(channel) {

  final def bind(address: SocketAddress): IO[Exception, Unit] =
    IO.effect(channel.bind(address.jSocketAddress)).refineToOrDie[Exception].unit

  final def setOption[T](name: SocketOption[T], value: T): IO[Exception, Unit] =
    IO.effect(channel.setOption(name.jSocketOption, value)).refineToOrDie[Exception].unit

  final def shutdownInput: IO[Exception, Unit] =
    IO.effect(channel.shutdownInput()).refineToOrDie[Exception].unit

  final def shutdownOutput: IO[Exception, Unit] =
    IO.effect(channel.shutdownOutput()).refineToOrDie[Exception].unit

  final def remoteAddress: IO[Exception, Option[SocketAddress]] =
    IO.effect(
        Option(channel.getRemoteAddress)
          .map(new SocketAddress(_))
      )
      .refineToOrDie[Exception]

  final def localAddress: IO[Exception, Option[SocketAddress]] =
    IO.effect(
        Option(channel.getLocalAddress)
          .map(new SocketAddress(_))
      )
      .refineToOrDie[Exception]

  final def connect(socketAddress: SocketAddress): IO[Exception, Unit] =
    withCompletionHandler[JVoid](h => channel.connect(socketAddress.jSocketAddress, (), h)).unit
      .refineToOrDie[Exception]

  final private[nio] def readBuffer[A](dst: Buffer[Byte], timeout: Duration): IO[Exception, Int] =
    withCompletionHandler[JInteger] { h =>
      channel.read(
        dst.buffer.asInstanceOf[JByteBuffer],
        timeout.fold(Long.MaxValue, _.nanos),
        TimeUnit.NANOSECONDS,
        (),
        h
      )
    }.map(_.toInt).refineToOrDie[Exception]

  final def read[A](capacity: Int, timeout: Duration): IO[Exception, Chunk[Byte]] =
    for {
      b <- Buffer.byte(capacity)
      l <- readBuffer(b, timeout)
      a <- b.array
      r <- if (l == -1) {
            ZIO.fail(new IOException("Connection reset by peer"))
          } else {
            ZIO.succeed(Chunk.fromArray(a).take(l))
          }
    } yield r

  final private[nio] def readBuffer[A](
    dsts: List[Buffer[Byte]],
    offset: Int,
    length: Int,
    timeout: Duration
  ): IO[Exception, Long] =
    withCompletionHandler[JLong](h =>
      channel.read(
        dsts.map(_.buffer.asInstanceOf[JByteBuffer]).toArray,
        offset,
        length,
        timeout.fold(Long.MaxValue, _.nanos),
        TimeUnit.NANOSECONDS,
        (),
        h
      )
    ).map(_.toLong).refineToOrDie[Exception]

  final def read[A](
    capacities: List[Int],
    offset: Int,
    length: Int,
    timeout: Duration
  ): IO[Exception, List[Chunk[Byte]]] =
    for {
      bs <- IO.collectAll(capacities.map(Buffer.byte))
      l  <- readBuffer(bs, offset, length, timeout)
      as <- IO.collectAll(bs.map(_.array))
      r <- if (l == -1) {
            ZIO.fail(new IOException("Connection reset by peer"))
          } else {
            ZIO.succeed(as.map(Chunk.fromArray))
          }
    } yield r
}

object AsynchronousSocketChannel {

  def apply(): Managed[Exception, AsynchronousSocketChannel] = {
    val open = IO
      .effect(JAsynchronousSocketChannel.open())
      .refineToOrDie[Exception]
      .map(new AsynchronousSocketChannel(_))

    Managed.make(open)(_.close.orDie)
  }

  def apply(channelGroup: AsynchronousChannelGroup): Managed[Exception, AsynchronousSocketChannel] = {
    val open = IO
      .effect(
        JAsynchronousSocketChannel.open(channelGroup.channelGroup)
      )
      .refineOrDie {
        case e: Exception => e
      }
      .map(new AsynchronousSocketChannel(_))

    Managed.make(open)(_.close.orDie)
  }

  def apply(asyncSocketChannel: JAsynchronousSocketChannel): AsynchronousSocketChannel =
    new AsynchronousSocketChannel(asyncSocketChannel)
}
