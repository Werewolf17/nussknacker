package pl.touk.nussknacker.engine.spel

import java.math.BigDecimal
import java.text.ParseException
import java.time.{LocalDate, LocalDateTime}
import java.util
import java.util.Collections

import cats.data.Validated.{Invalid, Valid}
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.IO
import org.apache.avro.generic.GenericData
import org.scalatest.{EitherValues, FunSuite, Matchers}
import pl.touk.nussknacker.engine.api.{Context, ParamName}
import pl.touk.nussknacker.engine.api.context.ProcessCompilationError.NodeId
import pl.touk.nussknacker.engine.api.context.ValidationContext
import pl.touk.nussknacker.engine.api.dict.embedded.EmbeddedDictDefinition
import pl.touk.nussknacker.engine.api.dict.{DictDefinition, DictInstance}
import pl.touk.nussknacker.engine.api.expression.{Expression, ExpressionParseError, TypedExpression, ValueWithLazyContext}
import pl.touk.nussknacker.engine.api.lazyy.{LazyContext, LazyValuesProvider, UsingLazyValues}
import pl.touk.nussknacker.engine.api.process.ClassExtractionSettings
import pl.touk.nussknacker.engine.api.typed.TypedMap
import pl.touk.nussknacker.engine.api.typed.typing.{Typed, TypedObjectTypingResult}
import pl.touk.nussknacker.engine.dict.SimpleDictRegistry
import pl.touk.nussknacker.engine.spel.SpelExpressionParser.{Flavour, Standard}
import pl.touk.nussknacker.engine.types.{GeneratedAvroClass, JavaClassWithVarargs}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.reflect.runtime.universe._

class SpelExpressionSpec extends FunSuite with Matchers with EitherValues {

  private class EvaluateSync(expression: Expression) {
    def evaluateSync[T](ctx: Context = ctx, lvp: LazyValuesProvider = dumbLazyProvider) : ValueWithLazyContext[T]
      = Await.result(expression.evaluate[T](ctx, lvp), 5 seconds)

    def evaluateSyncToValue[T](ctx: Context = ctx, lvp: LazyValuesProvider = dumbLazyProvider) : T
      = evaluateSync(ctx, lvp).value
  }

  private implicit val nid: NodeId = NodeId("")

  private implicit val classLoader: ClassLoader = getClass.getClassLoader

  private implicit def toEvaluateSync(expression: Expression) : EvaluateSync = new EvaluateSync(expression)

  private val bigValue = BigDecimal.valueOf(4187338076L)

  private val testValue = Test( "1", 2, List(Test("3", 4), Test("5", 6)).asJava, bigValue)
  private val ctx = Context("abc").withVariables(
    Map("obj" -> testValue,"strVal" -> "","mapValue" -> Map("foo" -> "bar").asJava)
  )
  private val ctxWithGlobal : Context = ctx
    .withVariable("processHelper", SampleGlobalObject)
    .withVariable("javaClassWithVarargs", new JavaClassWithVarargs)

  private def dumbLazyProvider: LazyValuesProvider = new LazyValuesProvider {
    override def apply[T](ctx: LazyContext, serviceId: String, params: Seq[(String, Any)]) = throw new IllegalStateException("Shouln't be invoked")
  }

  private val enrichingServiceId = "serviceId"

  case class Test(id: String, value: Long, children: java.util.List[Test] = List[Test]().asJava, bigValue: BigDecimal = BigDecimal.valueOf(0L)) extends UsingLazyValues {
    val lazyVal: LazyState[String] = lazyValue[String](enrichingServiceId).map(_ + " ma kota")
  }

  private def parseOrFail[T:TypeTag](expr: String, context: Context = ctx, flavour: Flavour = Standard) : Expression = {
    parse(expr, context, flavour) match {
      case Valid(e) => e.expression
      case Invalid(err) => throw new ParseException(err.map(_.message).toList.mkString, -1)
    }
  }

  private def parseOrFail[T:TypeTag](expr: String, context: ValidationContext) : Expression = {
    parse(expr, context) match {
      case Valid(e) => e.expression
      case Invalid(err) => throw new ParseException(err.map(_.message).toList.mkString, -1)
    }
  }


  import pl.touk.nussknacker.engine.util.Implicits._

  private def parseWithDicts[T: TypeTag](expr: String, context: Context = ctx, dictionaries: Map[String, DictDefinition]): ValidatedNel[ExpressionParseError, TypedExpression] = {
    val validationCtx = ValidationContext(
      context.variables.mapValuesNow(Typed.fromInstance))
    parse(expr, validationCtx, dictionaries, Standard, strictMethodsChecking = true)
  }

  private def parseWithoutStrictMethodsChecking[T: TypeTag](expr: String, context: Context = ctx, flavour: Flavour = Standard): ValidatedNel[ExpressionParseError, TypedExpression] = {
    val validationCtx = ValidationContext(context.variables.mapValuesNow(Typed.fromInstance))
    parse(expr, validationCtx, Map.empty, flavour, strictMethodsChecking = false)
  }

  private def parse[T: TypeTag](expr: String, context: Context = ctx, flavour: Flavour = Standard): ValidatedNel[ExpressionParseError, TypedExpression] = {
    val validationCtx = ValidationContext(
      context.variables.mapValuesNow(Typed.fromInstance))
    parse(expr, validationCtx, Map.empty, flavour, strictMethodsChecking = true)
  }

  private def parse[T: TypeTag](expr: String, validationCtx: ValidationContext): ValidatedNel[ExpressionParseError, TypedExpression] = {
    parse(expr, validationCtx, Map.empty, Standard, strictMethodsChecking = true)
  }

  private def parse[T: TypeTag](expr: String, validationCtx: ValidationContext, dictionaries: Map[String, DictDefinition],
                                flavour: Flavour, strictMethodsChecking: Boolean): ValidatedNel[ExpressionParseError, TypedExpression] = {
    val imports = List(SampleValue.getClass.getPackage.getName)
    SpelExpressionParser.default(getClass.getClassLoader, new SimpleDictRegistry(dictionaries), enableSpelForceCompile = true,
      strictTypeChecking = true, imports, flavour, strictMethodsChecking = strictMethodsChecking)(ClassExtractionSettings.Default).parse(expr, validationCtx, Typed.fromDetailedType[T])
  }

  test("invoke simple expression") {
    parseOrFail[java.lang.Number]("#obj.value + 4").evaluateSyncToValue[Long](ctx) should equal(6)
  }

  test("invoke simple list expression") {
    parseOrFail[Boolean]("{'1', '2'}.contains('2')").evaluateSyncToValue[Boolean](ctx) shouldBe true
  }

  test("handle string concatenation correctly") {
    parse[String]("'' + 1") shouldBe 'valid
    parse[Long]("2 + 1") shouldBe 'valid
    parse[String]("'' + ''") shouldBe 'valid
    parse[String]("4 + ''") shouldBe 'valid
  }

  test("subtraction of non numeric types") {
    parse[Any]("'' - 1") shouldEqual Invalid(NonEmptyList.of(ExpressionParseError("Operator '-' used with mismatch types: java.lang.String and java.lang.Integer")))
  }

  test("null properly") {
    parse[String]("null") shouldBe 'valid
    parse[Long]("null") shouldBe 'valid
    parse[Any]("null") shouldBe 'valid
    parse[Boolean]("null") shouldBe 'valid
  }

  test("invoke list variable reference with different concrete type after compilation") {
    def contextWithList(value: Any) = ctx.withVariable("list", value)
    val expr = parseOrFail[Any]("#list", contextWithList(Collections.emptyList()))

    //first run - nothing happens, we bump the counter
    expr.evaluateSyncToValue[Any](contextWithList(null))
    //second run - exitTypeDescriptor is set, expression is compiled
    expr.evaluateSyncToValue[Any](contextWithList(new util.ArrayList[String]()))
    //third run - expression is compiled as ArrayList and we fail :(
    expr.evaluateSyncToValue[Any](contextWithList(Collections.emptyList()))
  }

  // TODO: fixme
  ignore("perform date operations") {
    val twoDaysAgo = LocalDate.now().minusDays(2)
    val withDays = ctx.withVariable("date", twoDaysAgo)
    parseOrFail[Any]("#date.until(T(java.time.LocalDate).now())", withDays).evaluateSyncToValue[Integer](withDays)should equal(2)
  }

  // TODO: fixme
  ignore("register functions") {
    val twoDaysAgo = LocalDate.now().minusDays(2)
    val withDays = ctx.withVariable("date", twoDaysAgo)
    parseOrFail[Any]("#date.until(#today()).days", withDays).evaluateSync[Integer](withDays) should equal(2)
  }

  test("be possible to use SpEL's #this object") {
    parseOrFail[Any]("{1, 2, 3}.?[ #this > 1]").evaluateSyncToValue[java.util.List[Integer]](ctx) shouldBe util.Arrays.asList(2, 3)
    parseOrFail[Any]("{1, 2, 3}.![ #this > 1]").evaluateSyncToValue[java.util.List[Boolean]](ctx) shouldBe util.Arrays.asList(false, true, true)
    parseOrFail[Any]("{'1', '22', '3'}.?[ #this.length > 1]").evaluateSyncToValue[java.util.List[Boolean]](ctx) shouldBe util.Arrays.asList("22")
    parseOrFail[Any]("{'1', '22', '3'}.![ #this.length > 1]").evaluateSyncToValue[java.util.List[Boolean]](ctx) shouldBe util.Arrays.asList(false, true, false)

  }

  test("validate MethodReference") {
    val parsed = parse[Any]("#processHelper.add(1, 1)", ctxWithGlobal)
    parsed.isValid shouldBe true

    val invalid = parse[Any]("#processHelper.addT(1, 1)", ctxWithGlobal)
    invalid shouldEqual Invalid(NonEmptyList.of(ExpressionParseError("Unknown method 'addT' in pl.touk.nussknacker.engine.spel.SampleGlobalObject$")))

  }

  test("validate MethodReference parameter types") {
    parse[Any]("#processHelper.add(1, 1)", ctxWithGlobal) shouldBe 'valid
    parse[Any]("#processHelper.add(1L, 1)", ctxWithGlobal) shouldBe 'valid
    parse[Any]("#processHelper.addLongs(1L, 1L)", ctxWithGlobal) shouldBe 'valid
    parse[Any]("#processHelper.addLongs(1, 1L)", ctxWithGlobal) shouldBe 'valid
    parse[Any]("#processHelper.add(#processHelper.toAny('1'), 1)", ctxWithGlobal) shouldBe 'valid

    val invalid = parse[Any]("#processHelper.add('1', 1)", ctxWithGlobal)
    invalid shouldEqual Invalid(NonEmptyList.of(ExpressionParseError("Mismatch parameter types. Found: add(java.lang.String, java.lang.Integer). Required: add(int, int)")))
  }

  // TODO handle scala varargs
  ignore("validate MethodReference for scala varargs") {
    parse[Any]("#processHelper.addAll(1, 2, 3)", ctxWithGlobal) shouldBe 'valid
  }

  test("validate MethodReference for java varargs") {
    parse[Any]("#javaClassWithVarargs.addAll(1, 2, 3)", ctxWithGlobal) shouldBe 'valid
  }

  test("skip MethodReference validation without strictMethodsChecking") {
    val parsed = parseWithoutStrictMethodsChecking[Any]("#processHelper.notExistent(1, 1)", ctxWithGlobal)
    parsed.isValid shouldBe true
  }

  test("return invalid type for MethodReference with invalid arity ") {
    val parsed = parse[Any]("#processHelper.add(1)", ctxWithGlobal)
    val expectedValidation = Invalid("Mismatch parameter types. Found: add(java.lang.Integer). Required: add(int, int)")
    parsed.isInvalid shouldBe true
    parsed.leftMap(_.head).leftMap(_.message) shouldEqual expectedValidation
  }

  test("return invalid type for MethodReference with missing arguments") {
    val parsed = parse[Any]("#processHelper.add()", ctxWithGlobal)
    val expectedValidation = Invalid("Mismatch parameter types. Found: add(). Required: add(int, int)")
    parsed.isInvalid shouldBe true
    parsed.leftMap(_.head).leftMap(_.message) shouldEqual expectedValidation
  }

  test("return invalid type if PropertyOrFieldReference does not exists") {
    val parsed = parse[Any]("#processHelper.add", ctxWithGlobal)
    val expectedValidation =  Invalid("There is no property 'add' in type: pl.touk.nussknacker.engine.spel.SampleGlobalObject$")
    parsed.isInvalid shouldBe true
    parsed.leftMap(_.head).leftMap(_.message) shouldEqual expectedValidation
  }

  test("handle big decimals") {
    bigValue.compareTo(BigDecimal.valueOf(50*1024*1024)) should be > 0
    bigValue.compareTo(BigDecimal.valueOf(50*1024*1024L)) should be > 0
    parseOrFail[Any]("#obj.bigValue").evaluateSyncToValue[BigDecimal](ctx) should equal(bigValue)
    parseOrFail[Boolean]("#obj.bigValue < 50*1024*1024").evaluateSyncToValue[Boolean](ctx) should equal(false)
    parseOrFail[Boolean]("#obj.bigValue < 50*1024*1024L").evaluateSyncToValue[Boolean](ctx) should equal(false)
  }

  test("access list elements by index") {
    parseOrFail[String]("#obj.children[0].id").evaluateSyncToValue[String](ctx) shouldEqual "3"
    parseOrFail[String]("#mapValue['foo']").evaluateSyncToValue[String](ctx) shouldEqual "bar"
    parse[Int]("#obj.children[0].id") shouldBe 'invalid

  }

  test("filter by list predicates") {

    parseOrFail[Any]("#obj.children.?[id == '55'].isEmpty").evaluateSyncToValue[Boolean](ctx) should equal(true)
    parseOrFail[Any]("#obj.children.?[id == '55' || id == '66'].isEmpty").evaluateSyncToValue[Boolean](ctx) should equal(true)
    parseOrFail[Any]("#obj.children.?[id == '5'].size()").evaluateSyncToValue[Integer](ctx) should equal(1: Integer)
    parseOrFail[Any]("#obj.children.?[id == '5' || id == '3'].size()").evaluateSyncToValue[Integer](ctx) should equal(2: Integer)
    parseOrFail[Any]("#obj.children.?[id == '5' || id == '3'].![value]")
      .evaluateSyncToValue[util.ArrayList[Long]](ctx) should equal(new util.ArrayList(util.Arrays.asList(4L, 6L)))
    parseOrFail[Any]("(#obj.children.?[id == '5' || id == '3'].![value]).contains(4L)")
      .evaluateSyncToValue[Boolean](ctx) should equal(true)

  }

  test("evaluate map ") {
    val ctxWithVar = ctx.withVariable("processVariables", Collections.singletonMap("processingStartTime", 11L))
    parseOrFail[Any]("#processVariables['processingStartTime']", ctxWithVar).evaluateSyncToValue[Long](ctxWithVar) should equal(11L)
  }

  test("stop validation when property of Any/Object type found") {
    val ctxWithVar = ctx.withVariable("obj", SampleValue(11))
    parse[Any]("#obj.anyObject.anyPropertyShouldValidate", ctxWithVar) shouldBe 'valid

  }

  test("allow empty expression ") {
    parse[Any]("", ctx) shouldBe 'valid
  }

  test("register static variables") {
    parseOrFail[Any]("#processHelper.add(1, #processHelper.constant())", ctxWithGlobal).evaluateSyncToValue[Integer](ctxWithGlobal) should equal(5)
  }

  test("allow access to maps in dot notation") {
    val withMapVar = ctx.withVariable("map", Map("key1" -> "value1", "key2" -> 20).asJava)

    parseOrFail[String]("#map.key1", withMapVar).evaluateSyncToValue[String](withMapVar) should equal("value1")
    parseOrFail[Integer]("#map.key2", withMapVar).evaluateSyncToValue[Integer](withMapVar) should equal(20)
  }

  test("check return type for map property accessed in dot notation") {
    parse[String]("#processHelper.stringOnStringMap.key1", ctxWithGlobal) shouldBe 'valid
    parse[Integer]("#processHelper.stringOnStringMap.key1", ctxWithGlobal) shouldBe 'invalid
  }

  test("allow access to objects with get method in dot notation") {
    val withObjVar = ctx.withVariable("obj", new SampleObjectWithGetMethod(Map("key1" -> "value1", "key2" -> 20)))

    parseOrFail[String]("#obj.key1", withObjVar).evaluateSyncToValue[String](withObjVar) should equal("value1")
    parseOrFail[Integer]("#obj.key2", withObjVar).evaluateSyncToValue[Integer](withObjVar) should equal(20)
  }

  test("check property if is defined even if class has get method") {
    val withObjVar = ctx.withVariable("obj", new SampleObjectWithGetMethod(Map.empty))

    parse[Boolean]("#obj.definedProperty == 123", withObjVar) shouldBe 'invalid
    parseOrFail[Boolean]("#obj.definedProperty == '123'", withObjVar).evaluateSyncToValue[Boolean](withObjVar) shouldBe true
  }

  test("check property if is defined even if class has get method - avro generic record") {
    val record = new GenericData.Record(GeneratedAvroClass.SCHEMA$)
    record.put("text", "foo")
    val withObjVar = ctx.withVariable("obj", record)

    parseOrFail[String]("#obj.text", withObjVar).evaluateSyncToValue[String](withObjVar) shouldEqual "foo"
  }

  test("exact check properties in generated avro classes") {
    val withObjVar = ctx.withVariable("obj", GeneratedAvroClass.newBuilder().setText("123").build())

    parse[Boolean]("#obj.notExistingProperty == 123", withObjVar) shouldBe 'invalid
    parseOrFail[Boolean]("#obj.getText == '123'", withObjVar).evaluateSyncToValue[Boolean](withObjVar) shouldBe true
  }

  test("allow access to statics") {
    val withMapVar = ctx.withVariable("longClass", classOf[java.lang.Long])
    parseOrFail[Any]("#longClass.valueOf('44')", withMapVar)
      .evaluateSyncToValue[Long](withMapVar) should equal(44l)

    parseOrFail[Any]("T(java.lang.Long).valueOf('44')", ctx)
      .evaluateSyncToValue[Long](ctx) should equal(44l)
  }

  test("should != correctly for compiled expression - expression is compiled when invoked for the 3rd time") {
    //see https://jira.spring.io/browse/SPR-9194 for details
    val empty = new String("")
    val withMapVar = ctx.withVariable("emptyStr", empty)

    val expression = parseOrFail[Boolean]("#emptyStr != ''", withMapVar)
    expression.evaluateSyncToValue[Boolean](withMapVar) should equal(false)
    expression.evaluateSyncToValue[Boolean](withMapVar) should equal(false)
    expression.evaluateSyncToValue[Boolean](withMapVar) should equal(false)
  }

  test("evaluate using lazy value") {
    val provided = "ala"
    val lazyValueProvider = new LazyValuesProvider {
      override def apply[T](context: LazyContext, serviceId: String, params: Seq[(String, Any)]): IO[(LazyContext, T)] =
        IO.pure((context.withEvaluatedValue(enrichingServiceId, params.toMap, Left(provided)), provided.asInstanceOf[T]))
    }

    val valueWithModifiedContext = parseOrFail[Any]("#obj.lazyVal").evaluateSync[String](ctx, lazyValueProvider)
    valueWithModifiedContext.value shouldEqual "ala ma kota"
    valueWithModifiedContext.lazyContext[String](enrichingServiceId, Map.empty) shouldEqual provided
  }

  test("not allow access to variables without hash in methods") {
    val withNum = ctx.withVariable("a", 5).withVariable("processHelper", SampleGlobalObject)
    parse[Any]("#processHelper.add(a, 1)", withNum) should matchPattern {
      case Invalid(l: NonEmptyList[_]) if l.toList.contains(ExpressionParseError("Non reference 'a' occurred. Maybe you missed '#' in front of it?")) =>
    }
  }

  test("not allow unknown variables in methods") {
    parse[Any]("#processHelper.add(#a, 1)", ctx.withVariable("processHelper", SampleGlobalObject.getClass)) should matchPattern {
      case Invalid(NonEmptyList(ExpressionParseError("Unresolved reference 'a'"), Nil)) =>
    }

    parse[Any]("T(pl.touk.nussknacker.engine.spel.SampleGlobalObject).add(#a, 1)", ctx) should matchPattern {
      case Invalid(NonEmptyList(ExpressionParseError("Unresolved reference 'a'"), Nil)) =>
    }
  }

  test("not allow vars without hashes in equality condition") {
    parse[Any]("nonexisting == 'ala'", ctx) should matchPattern {
      case Invalid(NonEmptyList(ExpressionParseError("Non reference 'nonexisting' occurred. Maybe you missed '#' in front of it?"), Nil)) =>
    }
  }

  test("validate simple literals") {
    parse[Long]("-1", ctx) shouldBe 'valid
    parse[Float]("-1.1", ctx) shouldBe 'valid
    parse[Long]("-1.1", ctx) should not be 'valid
    parse[Double]("-1.1", ctx) shouldBe 'valid
    parse[java.math.BigDecimal]("-1.1", ctx) shouldBe 'valid
  }

  test("validate ternary operator") {
    parse[Long]("'d'? 3 : 4", ctx) should not be 'valid
    parse[String]("1 > 2 ? 12 : 23", ctx) should not be 'valid
    parse[Long]("1 > 2 ? 12 : 23", ctx) shouldBe 'valid
    parse[Number]("1 > 2 ? 12 : 23.0", ctx) shouldBe 'valid
    parse[String]("1 > 2 ? 'ss' : 'dd'", ctx) shouldBe 'valid
    parse[Any]("1 > 2 ? '123' : 123", ctx) shouldBe 'invalid
  }

  test("validate selection for inline list") {
    parse[Long]("{44, 44}.?[#this.alamakota]", ctx) should not be 'valid
    parse[java.util.List[_]]("{44, 44}.?[#this > 4]", ctx) shouldBe 'valid


  }

  test("validate selection and projection for list variable") {
    val vctx = ValidationContext.empty.withVariable("a", Typed.fromDetailedType[java.util.List[String]]).toOption.get

    parse[java.util.List[Int]]("#a.![#this.length()].?[#this > 4]", vctx) shouldBe 'valid
    parse[java.util.List[Boolean]]("#a.![#this.length()].?[#this > 4]", vctx) shouldBe 'invalid
    parse[java.util.List[Int]]("#a.![#this / 5]", vctx) should not be 'valid
  }

  test("allow #this reference inside functions") {
    parseOrFail[java.util.List[String]]("{1, 2, 3}.!['ala'.substring(#this - 1)]", ctx)
      .evaluateSyncToValue[java.util.List[String]](ctx).asScala.toList shouldBe List("ala", "la", "a")
  }

  test("allow property access in unknown classes") {
    parse[Any]("#input.anyObject", ValidationContext(Map("input" -> Typed[SampleValue]))) shouldBe 'valid
  }

  test("validate expression with projection and filtering") {
    val ctxWithInput = ctx.withVariable("input", SampleObject(List(SampleValue(444))))
    parse[Any]("(#input.list.?[value == 5]).![value].contains(5)", ctxWithInput) shouldBe 'valid
  }

  test("validate map literals") {
    val ctxWithInput = ctx.withVariable("input", SampleValue(444))
    parse[Any]("{ Field1: 'Field1Value', Field2: 'Field2Value', Field3: #input.value }", ctxWithInput) shouldBe 'valid
  }

  test("type map literals") {
    val ctxWithInput = ctx.withVariable("input", SampleValue(444))
    parse[Any]("{ Field1: 'Field1Value', Field2: #input.value }.Field1", ctxWithInput) shouldBe 'valid
    parse[Any]("{ Field1: 'Field1Value', 'Field2': #input }.Field2.value", ctxWithInput) shouldBe 'valid
    parse[Any]("{ Field1: 'Field1Value', Field2: #input }.noField", ctxWithInput) shouldNot be ('valid)

  }


  test("validate lazy value usage") {
    val ctxWithInput = ctx.withVariable("input", SampleValue(444))
    parse[String]("#input.lazy1", ctxWithInput) shouldBe 'valid
    parse[Long]("#input.lazy2", ctxWithInput) shouldBe 'valid

  }

  test("not validate plain string ") {
    parse[Any]("abcd", ctx) shouldNot be ('valid)
  }

  test("can handle return generic return types") {
    parse[Any]("#processHelper.now.toLocalDate", ctxWithGlobal).map(_.returnType) should be (Valid(Typed[LocalDate]))
  }

  test("evaluate static field/method using property syntax") {
    parseOrFail[Any]("#processHelper.one", ctxWithGlobal).evaluateSyncToValue[Int](ctxWithGlobal) should equal(1)
    parseOrFail[Any]("#processHelper.one()", ctxWithGlobal).evaluateSyncToValue[Int](ctxWithGlobal) should equal(1)
    parseOrFail[Any]("#processHelper.constant", ctxWithGlobal).evaluateSyncToValue[Int](ctxWithGlobal) should equal(4)
    parseOrFail[Any]("#processHelper.constant()", ctxWithGlobal).evaluateSyncToValue[Int](ctxWithGlobal) should equal(4)
  }

  test("detect bad type of literal or variable") {

    def shouldHaveBadType(valid: Validated[NonEmptyList[ExpressionParseError], _], message: String) = valid should matchPattern {
      case Invalid(NonEmptyList(ExpressionParseError(msg), _)) if msg == message =>
    }

    shouldHaveBadType( parse[Int]("'abcd'", ctx), "Bad expression type, expected: int, found: java.lang.String" )
    shouldHaveBadType( parse[String]("111", ctx), "Bad expression type, expected: java.lang.String, found: java.lang.Integer" )
    shouldHaveBadType( parse[String]("{1, 2, 3}", ctx), "Bad expression type, expected: java.lang.String, found: java.util.List[java.lang.Integer]" )
    shouldHaveBadType( parse[java.util.Map[_, _]]("'alaMa'", ctx), "Bad expression type, expected: java.util.Map[java.lang.Object,java.lang.Object], found: java.lang.String" )
    shouldHaveBadType( parse[Int]("#strVal", ctx), "Bad expression type, expected: int, found: java.lang.String" )

  }

  test("resolve imported package") {
    val givenValue = 123
    parseOrFail[Int](s"new SampleValue($givenValue, '').value").evaluateSyncToValue[Int](ctx) should equal(givenValue)
  }

  test("parse typed map with existing field") {
    val ctxWithMap = ValidationContext
      .empty
      .withVariable("input", TypedObjectTypingResult(Map("str" -> Typed[String], "lon" -> Typed[Long]))).toOption.get


    parse[String]("#input.str", ctxWithMap) should be ('valid)
    parse[Long]("#input.lon", ctxWithMap) should be ('valid)

    parse[Long]("#input.str", ctxWithMap) shouldNot be ('valid)
    parse[String]("#input.ala", ctxWithMap) shouldNot be ('valid)
  }

  test("be able to convert between primitive types") {
    val ctxWithMap = ValidationContext
      .empty
      .withVariable("input", TypedObjectTypingResult(Map("int" -> Typed[Int]))).toOption.get

    val ctx = Context("").withVariable("input", TypedMap(Map("int" -> 1)))

    parseOrFail[Long]("#input.int.longValue", ctxWithMap).evaluateSyncToValue[Long](ctx) shouldBe 1L
  }

  test("evaluate parsed map") {
    val valCtxWithMap = ValidationContext
      .empty
      .withVariable("input", TypedObjectTypingResult(Map("str" -> Typed[String], "lon" -> Typed[Long]))).toOption.get

    val ctx = Context("").withVariable("input", TypedMap(Map("str" -> "aaa", "lon" -> 3444)))

    parseOrFail[String]("#input.str", valCtxWithMap).evaluateSyncToValue[String](ctx) shouldBe "aaa"
    parseOrFail[Long]("#input.lon", valCtxWithMap).evaluateSyncToValue[Long](ctx) shouldBe 3444
    parse[Any]("#input.notExisting", valCtxWithMap) shouldBe 'invalid
    parseOrFail[Boolean]("#input.containsValue('aaa')", valCtxWithMap).evaluateSyncToValue[Boolean](ctx) shouldBe true
    parseOrFail[Int]("#input.size", valCtxWithMap).evaluateSyncToValue[Int](ctx) shouldBe 2
    parseOrFail[Boolean]("#input == {str: 'aaa', lon: 3444}", valCtxWithMap).evaluateSyncToValue[Boolean](ctx) shouldBe true
  }

  test("be able to type toString()") {
    parse[Any]("12.toString()", ctx).toOption.get.returnType shouldBe Typed[String]
  }

  test("be able to type string concatenation") {
    parse[Any]("12 + ''", ctx).toOption.get.returnType shouldBe Typed[String]
    parse[Any]("'' + 12", ctx).toOption.get.returnType shouldBe Typed[String]
  }

  test("expand all fields of TypedObjects in union") {
    val ctxWithMap = ValidationContext
      .empty
      .withVariable("input", Typed(
        TypedObjectTypingResult(Map("str" -> Typed[String])),
        TypedObjectTypingResult(Map("lon" -> Typed[Long])))).toOption.get


    parse[String]("#input.str", ctxWithMap) should be ('valid)
    parse[Long]("#input.lon", ctxWithMap) should be ('valid)

    parse[Long]("#input.str", ctxWithMap) shouldNot be ('valid)
    parse[String]("#input.ala", ctxWithMap) shouldNot be ('valid)
  }

  test("expand all fields of TypedClass in union") {
    val ctxWithMap = ValidationContext
      .empty
      .withVariable("input", Typed(
        Typed[SampleObject],
        Typed[SampleValue])).toOption.get


    parse[List[_]]("#input.list", ctxWithMap) should be ('valid)
    parse[Int]("#input.value", ctxWithMap) should be ('valid)

    parse[Set[_]]("#input.list", ctxWithMap) shouldNot be ('valid)
    parse[String]("#input.value", ctxWithMap) shouldNot be ('valid)
  }

  test("parses expression with template context") {
    parse[String]("alamakota #{444}", ctx, SpelExpressionParser.Template) shouldBe 'valid
    parse[String]("alamakota #{444 + #obj.value}", ctx, SpelExpressionParser.Template) shouldBe 'valid
    parse[String]("alamakota #{444 + #nothing}", ctx, SpelExpressionParser.Template) shouldBe 'invalid

  }

  test("evaluates expression with template context") {
    parseOrFail[String]("alamakota #{444}", ctx, SpelExpressionParser.Template).evaluateSyncToValue[String]() shouldBe "alamakota 444"
    parseOrFail[String]("alamakota #{444 + #obj.value} #{#mapValue.foo}", ctx, SpelExpressionParser.Template).evaluateSyncToValue[String]() shouldBe "alamakota 446 bar"
  }

  test("evaluates empty template as empty string") {
    parseOrFail[String]("", ctx, SpelExpressionParser.Template).evaluateSyncToValue[String]() shouldBe ""
  }

  test("variables with TypeMap type") {
    val withObjVar = ctx.withVariable("dicts", TypedMap(Map("foo" -> SampleValue(123))))

    parseOrFail[Int]("#dicts.foo.value", withObjVar).evaluateSyncToValue[Int](withObjVar) should equal(123)
    parse[String]("#dicts.bar.value", withObjVar) shouldBe 'invalid
  }

  test("adding invalid type to number") {
    val floatAddExpr = "12.1 + #obj"
    parse[Float](floatAddExpr, ctx) shouldBe 'invalid
  }

  test("different types in equality") {
    parse[Boolean]("'123' == 234", ctx) shouldBe 'invalid
    parse[Boolean]("'123' == '234'", ctx) shouldBe 'valid
    parse[Boolean]("'123' == null", ctx) shouldBe 'valid

    parse[Boolean]("'123' != 234", ctx) shouldBe 'invalid
    parse[Boolean]("'123' != '234'", ctx) shouldBe 'valid
    parse[Boolean]("'123' != null", ctx) shouldBe 'valid

    parse[Boolean]("123 == 123123123123L", ctx) shouldBe 'valid
  }

  test("precise type parsing in two operand operators") {
    val floatAddExpr = "12.1 + 23.4"
    parse[Int](floatAddExpr, ctx) shouldBe 'invalid
    parse[Float](floatAddExpr, ctx) shouldBe 'valid
    parse[java.lang.Float](floatAddExpr, ctx) shouldBe 'valid
    parse[Double](floatAddExpr, ctx) shouldBe 'valid

    val floatMultiplyExpr = "12.1 * 23.4"
    parse[Int](floatMultiplyExpr, ctx) shouldBe 'invalid
    parse[Float](floatMultiplyExpr, ctx) shouldBe 'valid
    parse[java.lang.Float](floatMultiplyExpr, ctx) shouldBe 'valid
    parse[Double](floatMultiplyExpr, ctx) shouldBe 'valid
  }

  test("precise type parsing in single operand operators") {
    val floatAddExpr = "12.1++"
    parse[Int](floatAddExpr, ctx) shouldBe 'invalid
    parse[Float](floatAddExpr, ctx) shouldBe 'valid
    parse[java.lang.Float](floatAddExpr, ctx) shouldBe 'valid
    parse[Double](floatAddExpr, ctx) shouldBe 'valid
  }

  test("embedded dict values") {
    val embeddedDictId = "embeddedDictId"
    val dicts = Map(embeddedDictId -> EmbeddedDictDefinition(Map("fooId" -> "fooLabel")))
    val withObjVar = ctx.withVariable("embeddedDict", DictInstance(embeddedDictId, dicts(embeddedDictId)))

    parseWithDicts[String]("#embeddedDict['fooId']", withObjVar, dicts).toOption.get.expression.evaluateSyncToValue[String](withObjVar) shouldEqual "fooId"
    parseWithDicts[String]("#embeddedDict['wrongId']", withObjVar, dicts) shouldBe 'invalid
  }

  test("enum dict values") {
    val enumDictId = EmbeddedDictDefinition.enumDictId(classOf[SimpleEnum.Value])
    val dicts = Map(enumDictId -> EmbeddedDictDefinition.forScalaEnum[SimpleEnum.type](SimpleEnum).withValueClass[SimpleEnum.Value])
    val withObjVar = ctx
      .withVariable("stringValue", "one")
      .withVariable("enumValue", SimpleEnum.One)
      .withVariable("enum", DictInstance(enumDictId, dicts(enumDictId)))

    parseWithDicts[SimpleEnum.Value]("#enum['one']", withObjVar, dicts).toOption.get.expression.evaluateSyncToValue[SimpleEnum.Value](withObjVar) shouldEqual SimpleEnum.One
    parseWithDicts[SimpleEnum.Value]("#enum['wrongId']", withObjVar, dicts) shouldBe 'invalid

    parseWithDicts[Boolean]("#enumValue == #enum['one']", withObjVar, dicts).toOption.get.expression.evaluateSyncToValue[Boolean](withObjVar) shouldBe true
    parseWithDicts[Boolean]("#stringValue == #enum['one']", withObjVar, dicts) shouldBe 'invalid
  }

  test("invokes methods on primitives correctly") {
    def invokeAndCheck[T:TypeTag](expr: String, result: T): Unit = {
      val parsed = parseOrFail[T](expr)
      //Bytecode generation happens only after successful invoke at times. To be sure we're there we round it up to 5 ;)
      (1 to 5).foreach { _ =>
        parsed.evaluateSyncToValue[T](ctx) shouldBe result
      }
    }

    invokeAndCheck("1.toString", "1")
    invokeAndCheck("1.toString()", "1")
    invokeAndCheck("1.doubleValue", 1d)
    invokeAndCheck("1.doubleValue()", 1d)

    invokeAndCheck("false.toString", "false")
    invokeAndCheck("false.toString()", "false")
    invokeAndCheck("false.booleanValue", false)
    invokeAndCheck("false.booleanValue()", false)

    //not primitives, just to make sure toString works on other objects...
    invokeAndCheck("{}.toString", "[]")
    invokeAndCheck("#obj.id.toString", "1")
  }

}

case class SampleObject(list: List[SampleValue])

case class SampleValue(value: Int, anyObject: Any = "") extends UsingLazyValues {

  val lazy1 : LazyState[String] = lazyValue[String]("")

  val lazy2 : LazyState[Long] = lazyValue[Long]("")

}

object SimpleEnum extends Enumeration {
  // we must explicitly define Value class to recognize if type is matching
  class Value(name: String) extends Val(name)

  val One: Value = new Value("one")
  val Two: Value = new Value("two")
}

object SampleGlobalObject {
  val constant = 4
  def add(a: Int, b: Int): Int = a + b
  def addLongs(a: Long, b: Long) = a + b
  def addAll(a: Int*) = a.sum
  def one() = 1
  def now: LocalDateTime = LocalDateTime.now()
  def identityMap(map: java.util.Map[String, Any]): java.util.Map[String, Any] = map
  def toAny(value: Any): Any = value
  def stringOnStringMap: java.util.Map[String, String] = Map("key1" -> "value1", "key2" -> "value2").asJava
}

class SampleObjectWithGetMethod(map: Map[String, Any]) {

  def get(field: String): Any = map.getOrElse(field, throw new IllegalArgumentException(s"No such field: $field"))

  def definedProperty: String = "123"

}
