pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter {
                includeGroup("com.github.Kyant0")
                includeGroup("com.github.franmontiel")
            }
        }
    }
}

rootProject.name = "AHUTong"
// Retained migration/reference sources must not accidentally produce the old monolithic APK.
if (providers.gradleProperty("includeLegacyApp").orNull == "true") include(":app")
include (":core:model")
include(":core:icons")
include(":core:host-api")
include(":bootstrap-kernel")
include (":core:common")
include (":core:designsystem")
include (":core:network")
include (":core:storage")
include (":core:auth")
include (":core:plugin-api")
include (":data:personalization")
include (":data:schedule")
include (":data:grade")
include (":data:chaoxing")
include (":data:repository-index")
include (":feature:repository-index")
include (":feature:settings")
include (":feature:schedule")
include (":feature:grade")
include (":feature:xuexiaotong")
include (":data:recharge")
include (":data:update")
include (":background")
include (":feature:recharge")
include (":feature:circle")
include (":plugins:campus_circle")
include (":plugins:campus_guide")
include (":plugins:weather")
include (":plugins:repository")
include (":plugins:chaoxing")
include (":plugins:appearance")
include (":plugins:network_recharge")
include (":plugins:bathroom")
include (":plugins:electricity")
include (":plugins:campus_card")

include(":plugins:account")

include(":plugins:schedule")

include(":plugins:grade_exam")

include(":plugins:evaluation")

include(":plugins:program_completion")

include(":plugins:free_classroom")

include(":plugins:personalization")

include(":host")

include(":plugins:campus_notices")

include(":plugins:lost_found")

include(":plugins:storage_migration")

include(":plugins:student_mail")

include(":runtime:shared")
include(":runtime:camera")
include(":runtime:content")
include(":plugins:shared_runtime")
include(":plugins:ui_runtime")
include(":plugins:camera_runtime")
include(":plugins:content_runtime")
