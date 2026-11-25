package minesweeper.host

trait Transport {
  def send(bytes: Array[Byte]): Unit

  def readLine(timeoutMillis: Long): Option[String]
}
