package homework.http

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.client.dsl.io._
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.circe.CirceEntityCodec._
import org.http4s.Status.{NotFound, Successful}

import scala.concurrent.ExecutionContext
import io.circe.generic.auto._

import scala.util.Random

// Homework. Place the solution under `http` package in your homework repository.
//
// Write a server and a client that play a number guessing game together.
//
// Communication flow should be as follows:
// 1. The client asks the server to start a new game by providing the minimum and the maximum number that can
//    be guessed, as well as the maximum number of attempts.
// 2. The server comes up with some random number within the provided range.
// 3. The client starts guessing the number. Upon each attempt, the server evaluates the guess and responds to
//    the client, whether the current number is lower, greater or equal to the guessed one.
// 4. The game ends when the number is guessed or there are no more attempts left. At this point the client
//    should terminate, while the server may continue running forever.
// 5. The server should support playing many separate games (with different clients) at the same time.
//
// Use HTTP or WebSocket for communication. The exact protocol and message format to use is not specified and
// should be designed while working on the task.
final case class Template(min: Int, max: Int, attempts: Int)
final case class Game(min: Int, max: Int, attempts: Int, numberToGuess: Int, id: String)
object Game {
  def create(t: Template, number: Int, id: String): Game = Game(t.min, t.max, t.attempts, number, id)
}
final case class CurrentGames(games: List[Game]) {
  def addGame(game: Game): CurrentGames = CurrentGames.create(games :+ game)
  def removeGame(game: Game): CurrentGames = CurrentGames.create(games.filterNot(_ == game))
  def updateGame(game: Game): CurrentGames = {
    val updatedGame = game.copy(attempts = game.attempts - 1)

    removeGame(game).addGame(updatedGame)
  }
}
object CurrentGames {
  def create(games: List[Game]): CurrentGames = CurrentGames(games)
}

object GuessServer extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](ExecutionContext.global)
      .bindHttp(port = 3001, host = "localhost")
      .withHttpApp(httpApp)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)

  var currentGames: CurrentGames = CurrentGames.create(Nil)

  private val httpApp = HttpRoutes.of[IO] {

    // curl -POST -v "localhost:3001/game" -d '{"min": 0, "max": 5, "attempts": 3}' -H "Content-Type: application/json"
    case req @ POST -> Root / "game"                          =>
      req.as[Template]
        .flatMap(template => {
          val randomNumber = Random.between(template.min, template.max + 1)
          val gameID = java.util.UUID.randomUUID.toString
          val formattedGame = Game.create(template, randomNumber, gameID)
          currentGames = currentGames.addGame(formattedGame)

          Ok(s"Game has started, you may begin guessing between ${template.min} and ${template.max}")
            .map(_.addCookie("gameID", gameID))
        })

    // curl -v "localhost:3001/game/guess/2" -b "gameID=648afa12-94c1-43cf-97e0-243708ba77f0"
    case req @ GET -> Root / "game" / "guess" / IntVar(guess) =>
      val gameID = req.cookies.find(_.name == "gameID")
      val gameIDValue = gameID.flatMap(id => Option(id.content))

      Ok(s"$gameIDValue")

//      gameIDValue match {
//        case Some(id) => currentGames.games.find(_.id == id) match {
//            case Some(game) =>
//              if (guess == game.numberToGuess) {
//                currentGames = currentGames.removeGame(game)
//                Ok(s"Congratulations $guess was correct!").map(_.removeCookie("gameID"))
//              } else if (game.attempts == 1) {
//                if (guess > game.numberToGuess) {
//                  currentGames = currentGames.removeGame(game)
//                  Ok("Your guess was too high, you lose!").map(_.removeCookie("gameID"))
//                } else {
//                  currentGames = currentGames.removeGame(game)
//                  Ok("Your guess was too low, you lose!").map(_.removeCookie("gameID"))
//                }
//              } else {
//                if (guess > game.numberToGuess) {
//                  currentGames = currentGames.updateGame(game)
//                  Ok(s"Your guess was too high! Attempts left: ${game.attempts - 1}")
//                } else {
//                  currentGames = currentGames.updateGame(game)
//                  Ok(s"Your guess was too low! Attempts left: ${game.attempts - 1}")
//                }
//              }
//            case None       => BadRequest("Don't cheat the system!")
//          }
//        case None     => BadRequest("You are an alien!")
//      }
  }.orNotFound
}

object GuessClient extends IOApp {
  import org.http4s.Method._

  private val uri = uri"http://localhost:3001"

  private def printLine(string: String = ""): IO[Unit] = IO(println(string))

  def run(args: List[String]): IO[ExitCode] =
    BlazeClientBuilder[IO](ExecutionContext.global).resource.use(client =>
      for {
        _ <- printLine(string = "Providing game parameters:")
        _ <- client.expect[String](POST(Template(0, 5, 3), uri / "game"))
          .flatMap(printLine)
        _ <- printLine()

        _ <- printLine(string = "Trying to guess the number:")
        _ <- client.expect[String](GET(uri / "game" / "guess" / "4")).flatMap(printLine)
      } yield ()
    ).as(ExitCode.Success)
}