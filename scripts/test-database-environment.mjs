const DATABASE_TEST_ENVIRONMENT_NAMES = [
  "KAGIROI_MARIADB_TEST_URL",
  "KAGIROI_MYSQL_TEST_URL",
  "KAGIROI_ORACLE_TEST_CONNECT_STRING",
  "KAGIROI_ORACLE_TEST_PASSWORD",
  "KAGIROI_ORACLE_TEST_USER",
  "KAGIROI_POSTGRES_TEST_URL",
  "KAGIROI_SQLSERVER_TEST_DATABASE",
  "KAGIROI_SQLSERVER_TEST_HOST",
  "KAGIROI_SQLSERVER_TEST_PASSWORD",
  "KAGIROI_SQLSERVER_TEST_PORT",
  "KAGIROI_SQLSERVER_TEST_USER",
];

export function localDatabaseTestEnvironment(environment = process.env) {
  const localEnvironment = { ...environment };
  for (const name of DATABASE_TEST_ENVIRONMENT_NAMES) {
    delete localEnvironment[name];
  }
  return localEnvironment;
}
