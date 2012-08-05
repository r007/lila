package lila
package search

import game.{ GameRepo, DbGame }

import scalaz.effects._
import com.traackr.scalastic.elasticsearch.{ Indexer ⇒ EsIndexer }
import com.codahale.jerkson.Json

final class Indexer(es: EsIndexer, gameRepo: GameRepo) {

  val indexName = "lila"
  val typeName = "game"

  def rebuildAll: IO[Unit] = for {
    games ← gameRepo recentGames 10
    _ ← clear
    _ ← index(games)
  } yield Unit

  def clear: IO[Unit] = io {
    def prop(name: String, typ: String) = name -> Map("type" -> typ)
    val mapping = Json generate Map(
      typeName -> Map(
        "properties" -> List(
          prop("status", "int"),
          prop("turns", "int"),
          prop("rated", "bool"),
          prop("variant", "int")
        ).toMap
      )
    ) 
    println("will delete by query")
    es.deleteByQuery()
    println("will create index")
    es.createIndex(indexName, settings = Map("number_of_shards" -> "1"))
    println("will wait till active")
    es.waitTillActive()
    println("will put mapping")
    es.putMapping(indexName, typeName, mapping.pp)
    println("will refresh")
    es.refresh()
    println("done")
  }

  def index(games: List[DbGame]): IO[Unit] =
    games.map(index).sequence map (_ ⇒ Unit)

  def index(game: DbGame): IO[Unit] = io {
    es.index(indexName, typeName, game.id, gameJson(game).pp)
  }

  def gameJson(game: DbGame) = Json generate Map(
    "status" -> game.status.id,
    "turns" -> game.turns,
    "rated" -> game.mode.rated,
    "variant" -> game.variant.id
  )
}