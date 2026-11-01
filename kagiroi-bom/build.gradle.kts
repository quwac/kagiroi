plugins {
    `java-platform`
}

group = "dev.quwac.kagiroi"

dependencies {
    constraints {
        api(project(":kagiroi-core"))
        api(project(":kagiroi-schema"))
        api(project(":kagiroi-quwac-bignumber"))
        api(project(":kagiroi-kotlinx-datetime"))
        api(project(":kagiroi-sql"))
        api(project(":kagiroi-oracle"))
        api(project(":kagiroi-oracle-quwac-bignumber"))
        api(project(":kagiroi-oracle-kotlinx-datetime"))
        api(project(":kagiroi-node-oracle-driver"))
        api(project(":kagiroi-sqlserver"))
        api(project(":kagiroi-sqlserver-quwac-bignumber"))
        api(project(":kagiroi-sqlserver-kotlinx-datetime"))
        api(project(":kagiroi-sqlserver-node-driver"))
        api(project(":kagiroi-postgres"))
        api(project(":kagiroi-postgres-quwac-bignumber"))
        api(project(":kagiroi-postgres-kotlinx-datetime"))
        api(project(":kagiroi-postgres-hyperdrive-driver"))
        api(project(":kagiroi-postgres-node-driver"))
        api(project(":kagiroi-pglite-driver"))
        api(project(":kagiroi-mysql"))
        api(project(":kagiroi-mysql-quwac-bignumber"))
        api(project(":kagiroi-mysql-kotlinx-datetime"))
        api(project(":kagiroi-mysql-hyperdrive-driver"))
        api(project(":kagiroi-mysql-node-driver"))
        api(project(":kagiroi-mariadb"))
        api(project(":kagiroi-sqlite"))
        api(project(":kagiroi-sqlite-quwac-bignumber"))
        api(project(":kagiroi-sqlite-kotlinx-datetime"))
        api(project(":kagiroi-sqlite-inmemory-driver"))
        api(project(":kagiroi-sqlite-file-driver"))
        api(project(":kagiroi-d1-driver"))
    }
}
