package dev.quwac.kagiroi.sql

import dev.quwac.kagiroi.schema.OracleEnumDefinition

/** Native Oracle enumeration-domain DDL, requiring Oracle 23.4 or newer. */
sealed interface OracleEnumDdlStatement : DdlStatement

/** Creates an Oracle enumeration domain.
 * @property definition domain names and explicit text values.
 */
class CreateOracleEnumDomain(val definition: OracleEnumDefinition) : OracleEnumDdlStatement

/** Drops an Oracle enumeration domain without cascading to dependent columns.
 * @property definition domain to drop.
 */
class DropOracleEnumDomain(val definition: OracleEnumDefinition) : OracleEnumDdlStatement
