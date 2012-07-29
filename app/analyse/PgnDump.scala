package lila
package analyse

import chess.format.Forsyth
import chess.format.pgn
import chess.format.pgn.{ Pgn, Tag }
import chess.Clock
import chess.Color
import game.{ DbGame, DbPlayer, GameRepo }
import user.{ User, UserRepo }

import org.joda.time.format.DateTimeFormat
import scalaz.effects._

final class PgnDump(
    gameRepo: GameRepo,
    analyser: Analyser,
    userRepo: UserRepo) {

  import PgnDump._

  def >>(game: DbGame): IO[Pgn] = for {
    ts ← tags(game)
    pgnObj = Pgn(ts, turns(game))
    analysis ← analyser get game.id
  } yield analysis.fold(Annotator(pgnObj, _), pgnObj)

  def filename(game: DbGame): IO[String] = for {
    whiteUser ← user(game.whitePlayer)
    blackUser ← user(game.blackPlayer)
  } yield "lichess_pgn_%s_%s_vs_%s.%s.pgn".format(
    game.createdAt.fold(dateFormat.print, "_"),
    player(game.whitePlayer, whiteUser),
    player(game.blackPlayer, blackUser),
    game.id)

  private val baseUrl = "http://lichess.org/"
  private val dateFormat = DateTimeFormat forPattern "yyyy-MM-dd";

  private def elo(p: DbPlayer) = p.elo.fold(_.toString, "?")

  private def user(p: DbPlayer): IO[Option[User]] = p.userId.fold(
    userRepo.byId,
    io(none))

  private def player(p: DbPlayer, u: Option[User]) = p.aiLevel.fold(
    "AI level " + _,
    u.fold(_.username, "Anonymous"))

  private def timeControl(clockOpt: Option[Clock]) = clockOpt.fold(
    clock => "" + clock.limit + 
      (clock.increment > 0).fold("+" + clock.increment, ""),
    "-"
  )

  private def initialTime(clockOpt: Option[Clock]) = clockOpt.fold(
    clock => Clock.timeString(clock.limit),
    "--:--:--"
  )

  private def tags(game: DbGame): IO[List[Tag]] = for {
    whiteUser ← user(game.whitePlayer)
    blackUser ← user(game.blackPlayer)
    initialFen ← game.variant.standard.fold(io(none), gameRepo initialFen game.id)
  } yield List(
    Tag(_.Event, game.rated.fold("Rated game", "Casual game")),
    Tag(_.Site, baseUrl + game.id),
    Tag(_.Date, game.createdAt.fold(dateFormat.print, "?")),
    Tag(_.White, player(game.whitePlayer, whiteUser)),
    Tag(_.Black, player(game.blackPlayer, blackUser)),
    Tag(_.TimeControl, timeControl(game.clock)),
    Tag(_.WhiteClock, initialTime(game.clock)),
    Tag(_.BlackClock, initialTime(game.clock)),
    Tag(_.Result, result(game)),
    Tag("WhiteElo", elo(game.whitePlayer)),
    Tag("BlackElo", elo(game.blackPlayer)),
    Tag("PlyCount", game.turns),
    Tag(_.Variant, game.variant.name)
  ) ::: game.variant.standard.fold(Nil, List(
      Tag(_.FEN, initialFen | "?"),
      Tag("SetUp", "1")
    ))

  private def turns(game: DbGame): List[pgn.Turn] = {

    def allTimes = game.timesLeft(Color.White).flatten.zip(
      game.timesLeft(Color.Black).flatten
    ).flatMap{case (wTime, bTime) => List(Some(wTime), Some(bTime))} // Option[List[Int]]??

    def allMovesWithTimes = game.pgnList.zipAll(allTimes, "NOT_USED", None)

    allMovesWithTimes.grouped(2).zipWithIndex.toList.map {
      case (moves, index) ⇒ pgn.Turn(
        number = index + 1,
        white = moves.headOption map { 
          case (move,time) => pgn.Move(san=move,timeLeft=time)
        },
        black = moves.lastOption map {
          case (move,time) => pgn.Move(san=move,timeLeft=time)
        }
      )
    }
  }
}

object PgnDump {

  def result(game: DbGame) = game.finished.fold(
    game.winnerColor.fold(
      color ⇒ color.white.fold("1-0", "0-1"),
      "1/2-1/2"),
    "*")
}
