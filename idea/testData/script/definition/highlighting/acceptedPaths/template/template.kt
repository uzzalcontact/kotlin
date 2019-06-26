package custom.scriptDefinition

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.*

@KotlinScript(fileExtension = "script.kts", compilationConfiguration = MyTestDefinition::class)
abstract class MyTestScript(val args: Array<String>)

object MyTestDefinition : ScriptCompilationConfiguration(
    {
        refineConfiguration {
            beforeCompiling(MyTestConfigurator())
        }
        ide {
            acceptedLocations(ScriptAcceptedLocation.Everywhere)
        }
    })

class MyTestConfigurator : RefineScriptCompilationConfigurationHandler {
    override operator fun invoke(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        return context.compilationConfiguration.asSuccess(
            listOf(
                ScriptDiagnostic("ERROR", severity = Severity.WARNING)
            )
        )
    }
}
