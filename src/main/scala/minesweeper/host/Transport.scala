package minesweeper.host

/** Byte link to a device: serial port, simulated bridge or scripted mock. */
trait Transport {
  def send(bytes: Array[Byte]): Unit

  /** Next complete reply line without its LF (a CR may remain), or None after the timeout. */
  def readLine(timeoutMillis: Long): Option[String]
}
