

import org.mongodb.scala._
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters._
import org.slf4j.LoggerFactory
import play.api.libs.json._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object Main extends App {
  //Parameters:
  val connectionString = s"mongodb://${ConfigLoader.name}:${ConfigLoader.password}@${ConfigLoader.host}:${ConfigLoader.port}/?authMechanism=SCRAM-SHA-256&authSource=${ConfigLoader.database}"
  val dbObject = new Mongo()

  //Exeption Logger
  val logger = LoggerFactory.getLogger(getClass)

  // MongoDB-Verbindungsdaten
  var mongoClient = dbObject.getMongoClient(connectionString)
  val database = dbObject.getDatabase(mongoClient, "Projektstudium")
  val collection: MongoCollection[BsonDocument] = database.getCollection("tagesschau_test_data")


  // Crawler starten und ergebnis überprüfen
  val jsVal = Crawler.getTagesschauNewsPageApi("https://tagesschau.de/api2/news/")
  if (jsVal == JsNull || jsVal.toString == "{}") {
    logger.error("Crawler couldn't find any Data!")
  }

  // JsValue in ein JsArray umwandeln
  val jsonArray: JsResult[JsArray] = jsVal.validate[JsArray]

  var docWithNoText = 0
  var insertedDocs = 0
  var docAlreadyInDB = 0
  // Überprüfen, ob die Validierung erfolgreich war
  jsonArray.fold(
    errors => {
      // Fehlerbehandlung, falls die Validierung fehlschlägt
      logger.error(s"error while parsing the JsValue: $errors")
    },
    jsArray => {
      // JsArray wurde erfolgreich extrahiert
      jsArray.value.foreach { jsEntry =>
        // Überprüfen, ob das Dokument bereits in der Collection vorhanden ist
        //Wandle den JsValue in ein BsonDoc um
        val bsonDocument: BsonDocument = BsonDocument.apply(Json.stringify(jsEntry))
        val title = bsonDocument.get("title")
        val date = bsonDocument.get("date")
        val url = bsonDocument.get("url")
        val text = bsonDocument.get("text")
        val existingDocumentObservable = collection.find(
          and(
            equal("title", title),
            equal("date", date)
          )
        ).limit(1)
        val existingDocument = Await.result(existingDocumentObservable.toFuture(), Duration.Inf)

        // checken ob das dokument einen Text hat
        if (text.asString() != null && url.asString().getValue.nonEmpty) {

          // falls das Dokument  noch nicht in der Collection vorhanden ist, füge es hinzu
          if (existingDocument.isEmpty) {
            //Schreibe den eintrag in die Datenbank
            try {
              val insertObservable = collection.insertOne(bsonDocument)
              Await.result(insertObservable.toFuture(), Duration.Inf)
            } catch {
              case e: Exception =>
                logger.error("an exception occurred while trying to insert into MongoDB: ", e)
            }
            insertedDocs += 1
          } else {
            docAlreadyInDB += 1
          }

        } else {
          docWithNoText += 1
        }
      }
    }
  )
  logger.info("Inserted articles: " + insertedDocs)
  logger.info("Articles already in database: " + docAlreadyInDB)
  logger.info("Articles with no text body: " + docWithNoText)

  // Schließe die Verbindung zur MongoDB
  mongoClient.close()
}
