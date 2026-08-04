object EnvironmentFlags {

  private val TrueValues = Set("true", "1", "yes", "on")

  def isCi(environment: Map[String, String]): Boolean =
    environment.get("CI").exists(value => TrueValues.contains(value.trim.toLowerCase))
}
