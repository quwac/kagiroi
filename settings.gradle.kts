pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "kagiroi"
include(
    ":kagiroi-core",
    ":kagiroi-bom",
    ":kagiroi-schema",
    ":kagiroi-quwac-bignumber",
    ":kagiroi-kotlinx-datetime",
    ":kagiroi-sql",
    ":kagiroi-oracle",
    ":kagiroi-oracle-quwac-bignumber",
    ":kagiroi-oracle-kotlinx-datetime",
    ":kagiroi-node-oracle-driver",
    ":kagiroi-sqlserver",
    ":kagiroi-sqlserver-quwac-bignumber",
    ":kagiroi-sqlserver-kotlinx-datetime",
    ":kagiroi-sqlserver-node-driver",
    ":kagiroi-postgres",
    ":kagiroi-postgres-quwac-bignumber",
    ":kagiroi-postgres-kotlinx-datetime",
    ":kagiroi-postgres-hyperdrive-driver",
    ":kagiroi-postgres-node-driver",
    ":kagiroi-pglite-driver",
    ":kagiroi-mysql",
    ":kagiroi-mysql-quwac-bignumber",
    ":kagiroi-mysql-kotlinx-datetime",
    ":kagiroi-mysql-hyperdrive-driver",
    ":kagiroi-mysql-node-driver",
    ":kagiroi-mariadb",
    ":kagiroi-sqlite",
    ":kagiroi-sqlite-quwac-bignumber",
    ":kagiroi-sqlite-kotlinx-datetime",
    ":kagiroi-sqlite-inmemory-driver",
    ":kagiroi-sqlite-file-driver",
    ":kagiroi-d1-driver",
    ":integration-tests:postgres-hyperdrive",
    ":integration-tests:mysql-hyperdrive",
)

if (providers.gradleProperty("compileFixture").isPresent) {
    include(":compile-tests")
}
