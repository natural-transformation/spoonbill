package spoonbill.web.dsl

trait JsonCodec[J] {
  def encode(json: J): String
  def decode(source: String): J
}
