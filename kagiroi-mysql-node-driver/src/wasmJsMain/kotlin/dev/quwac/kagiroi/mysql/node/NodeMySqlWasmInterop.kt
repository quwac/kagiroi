@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@file:Suppress("TooManyFunctions", "UnusedParameter")

package dev.quwac.kagiroi.mysql.node

import kotlin.js.JsAny
import kotlin.js.Promise

internal fun poolConfig(
    connectionUrl: String?,
    host: String?,
    port: Int?,
    database: String?,
    user: String?,
    password: String?,
    maxConnections: Int,
    maxIdleConnections: Int,
    connectionTimeoutMilliseconds: Int,
    idleTimeoutMilliseconds: Int,
    tlsMode: String?,
    certificateAuthority: String?,
    clientCertificate: String?,
    clientPrivateKey: String?,
): JsAny = js(
    """
    ({
      uri: connectionUrl == null ? undefined : connectionUrl,
      host: host == null ? undefined : host,
      port: port == null ? undefined : port,
      database: database == null ? undefined : database,
      user: user == null ? undefined : user,
      password: password == null ? undefined : password,
      waitForConnections: true,
      connectionLimit: maxConnections,
      maxIdle: maxIdleConnections,
      connectTimeout: connectionTimeoutMilliseconds,
      idleTimeout: idleTimeoutMilliseconds,
      queueLimit: 0,
      disableEval: true,
      rowsAsArray: true,
      supportBigNumbers: true,
      bigNumberStrings: true,
      decimalNumbers: false,
      jsonStrings: true,
      dateStrings: true,
      timezone: 'Z',
      typeCast: (field, next) => field.type === 'GEOMETRY' ? field.buffer() : next(),
      ssl: tlsMode == null
        ? undefined
        : tlsMode === 'DISABLED'
          ? false
          : {
              rejectUnauthorized: tlsMode === 'VERIFY_FULL',
              verifyIdentity: tlsMode === 'VERIFY_FULL',
              ca: certificateAuthority == null ? undefined : certificateAuthority,
              cert: clientCertificate == null ? undefined : clientCertificate,
              key: clientPrivateKey == null ? undefined : clientPrivateKey,
            },
    })
    """,
)

internal fun jsType(value: JsAny): String = js("typeof value")

internal fun stringProperty(value: JsAny, name: String): String? =
    js("typeof value[name] === 'string' ? value[name] : null")

internal fun numberProperty(value: JsAny, name: String): Double? =
    js("typeof value[name] === 'number' ? value[name] : null")

internal fun booleanProperty(value: JsAny, name: String): Boolean? =
    js("typeof value[name] === 'boolean' ? value[name] : null")

internal fun isUint8Array(value: JsAny): Boolean = js("value instanceof Uint8Array")

internal fun isFiniteNumber(value: JsAny): Boolean = js("Number.isFinite(value)")

internal fun safeLongProperty(value: JsAny, name: String): Long? {
    val number = numberProperty(value, name) ?: return null
    require(isSafeInteger(number)) { "mysql2 returned an unsafe $name value" }
    return number.toLong()
}

internal fun isSafeInteger(value: Double): Boolean = js("Number.isSafeInteger(value)")

internal fun numberString(value: JsAny): String = js("String(value)")

internal fun booleanValue(value: JsAny): Boolean = js("value === true")

internal fun connectionWithTimeout(pool: NativeMySqlPool, timeoutMilliseconds: Int): Promise<NativeMySqlConnection> =
    js(
        """
    (() => {
      const pending = pool.getConnection();
      if (timeoutMilliseconds === 0) return pending;
      return new Promise((resolve, reject) => {
        let settled = false;
        const timer = setTimeout(() => {
          settled = true;
          const error = new Error('Timed out waiting for a MySQL pool connection');
          error.code = 'KAGIROI_POOL_ACQUISITION_TIMEOUT';
          error.timeoutMilliseconds = timeoutMilliseconds;
          reject(error);
        }, timeoutMilliseconds);
        pending.then(
          (connection) => {
            if (settled) {
              connection.release();
            } else {
              settled = true;
              clearTimeout(timer);
              resolve(connection);
            }
          },
          (error) => {
            if (!settled) {
              settled = true;
              clearTimeout(timer);
              reject(error);
            }
          },
        );
      });
    })()
    """,
    )
