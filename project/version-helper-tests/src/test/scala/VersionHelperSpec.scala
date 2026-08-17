import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class VersionHelperSpec extends AnyFlatSpec with Matchers {

  "CI detection" should "accept the conventional truthy values used by CI systems" in {
    Seq("true", "TRUE", "1", "yes", "on").foreach { value =>
      withClue(s"CI=$value: ") {
        EnvironmentFlags.isCi(Map("CI" -> value)) shouldBe true
      }
    }
  }

  it should "treat absent, false, and unrecognised values as disabled" in {
    Seq(None, Some("false"), Some("0"), Some("no"), Some("off"), Some("unexpected")).foreach { value =>
      withClue(s"CI=$value: ") {
        EnvironmentFlags.isCi(value.fold(Map.empty[String, String])(entry => Map("CI" -> entry))) shouldBe false
      }
    }
  }
}
