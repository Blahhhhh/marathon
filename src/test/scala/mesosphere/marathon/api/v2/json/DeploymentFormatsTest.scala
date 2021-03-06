package mesosphere.marathon.api.v2.json

import java.util.UUID

import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Group, Timestamp }
import mesosphere.marathon.test.MarathonSpec
import mesosphere.marathon.upgrade._
import org.scalatest.Matchers._
import play.api.libs.json._

import scala.collection.immutable.Seq
import scala.util.Random

class DeploymentFormatsTest extends MarathonSpec {
  import Formats._

  test("Can read GroupUpdate json") {
    val json = """
      |{
      |  "id": "a",
      |  "apps": [{ "id": "b", "version": "2015-06-03T13:00:52.883Z" }],
      |  "groups":[ { "id": "c", "version": "2015-06-03T13:00:52.928Z"}],
      |  "dependencies": [ "d" ],
      |  "scaleBy": 23.0,
      |  "version": "2015-06-03T13:00:52.928Z"
      |}
      |""".stripMargin
    val update = Json.parse(json).as[GroupUpdate]
    update.id should be(Some("a".toPath))
    update.apps should be ('defined)
    update.apps.get should have size 1
    update.apps.get.head.id should be("b".toPath)
    update.groups should be ('defined)
    update.groups.get should have size 1
    update.groups.get.head.id should be(Some("c".toPath))
    update.dependencies should be('defined)
    update.dependencies.get.head should be("d".toPath)
    update.scaleBy should be('defined)
    update.scaleBy.get should be(23.0 +- 0.01)
    update.version should be('defined)
    update.version.get should be(Timestamp("2015-06-03T13:00:52.928Z"))
  }

  test("Can write/read GroupUpdate") {
    marshalUnmarshal(genGroupUpdate())
    marshalUnmarshal(genGroupUpdate(Set(genGroupUpdate(), genGroupUpdate(Set(genGroupUpdate())))))
  }

  test("Will read from no given value") {
    val groupFromNull = JsNull.as[GroupUpdate]
    groupFromNull.id should be('empty)
    groupFromNull.apps should be('empty)
    groupFromNull.groups should be('empty)
    groupFromNull.dependencies should be('empty)
    groupFromNull.scaleBy should be('empty)
    groupFromNull.version should be('empty)
  }

  test("Can read Group json") {
    val json =
      """
        |{
        |  "id": "a",
        |  "apps": [
        |    { "id": "b", "version": "2015-06-03T13:18:25.639Z" }
        |  ],
        |  "groups": [{
        |    "id": "c",
        |    "version": "2015-06-03T13:18:26.642Z"
        |  }],
        |  "dependencies": [ "d" ],
        |  "version": "2015-06-03T13:18:25.640Z"
        |}
      """.stripMargin
    val group = Json.parse(json).as[Group]
    group.id should be("a".toPath)
    group.apps should have size 1
    group.apps.head._1 should be("b".toPath)
    group.groups should have size 1
    group.groupIds.head should be("c".toPath)
    group.dependencies.head should be("d".toPath)
    group.version should be(Timestamp("2015-06-03T13:18:25.640Z"))
  }

  test("Can write/read Group") {
    marshalUnmarshal(genGroup())
    marshalUnmarshal(genGroup(Set(genGroup(), genGroup(Set(genGroup())))))
  }

  test("Can write/read byte arrays") {
    marshalUnmarshal("Hello".getBytes)
  }

  test("DeploymentPlan can be serialized") {
    val plan = DeploymentPlan(
      genId.toString,
      genGroup(),
      genGroup(Set(genGroup(), genGroup())),
      Seq(genStep),
      Timestamp.now()
    )
    val json = Json.toJson(plan).as[JsObject]
    val fieldMap = json.fields.toMap
    fieldMap.keySet should be(Set("version", "id", "target", "original", "steps"))

    val action = ((json \ "steps")(0) \ "actions")(0)
    val actionFields = action.as[JsObject].fields.toMap.keySet
    actionFields should be(Set("action", "app"))
  }

  // regression test for #1176
  test("allow / as id") {
    val json = """{"id": "/"}"""
    assert(Json.parse(json).as[Group].id.isRoot)
  }

  def marshalUnmarshal[T](original: T)(implicit format: Format[T]): JsValue = {
    val json = Json.toJson(original)
    val read = json.as[T]
    read should be (original)
    json
  }

  def genInt = Random.nextInt(1000)

  def genId = UUID.randomUUID().toString.toPath

  def genTimestamp = Timestamp.now()

  def genApp = AppDefinition(id = genId)

  def genStep = DeploymentStep(actions = Seq(
    StartApplication(genApp, genInt),
    ScaleApplication(genApp, genInt),
    StopApplication(genApp),
    RestartApplication(genApp),
    ResolveArtifacts(genApp, Map.empty)
  ))

  def genGroup(children: Set[Group] = Set.empty) = {
    val app1 = genApp
    val app2 = genApp
    Group(genId, Map(app1.id -> app1, app2.id -> app2), children, Set(genId), genTimestamp)
  }

  def genGroupUpdate(children: Set[GroupUpdate] = Set.empty) =
    GroupUpdate(
      Some(genId),
      Some(Set(genApp, genApp)),
      Some(children),
      Some(Set(genId)),
      Some(23),
      Some(genTimestamp)
    )

}
