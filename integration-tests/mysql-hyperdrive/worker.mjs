import {
  runHyperdriveMariaDbContract,
  runHyperdriveMariaDbSmoke,
  runHyperdriveMySqlContract,
  runHyperdriveMySqlSmoke,
} from "./bridge.mjs";

export default {
  async fetch(request, environment) {
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", { status: 405 });
    }
    try {
      const path = new URL(request.url).pathname;
      const mariaDb = path === "/mariadb" || path === "/mariadb-smoke";
      const smoke = path === "/smoke" || path === "/mariadb-smoke";
      const database = mariaDb ? "MariaDB" : "MySQL";
      const operation = mariaDb
        ? smoke
          ? runHyperdriveMariaDbSmoke
          : runHyperdriveMariaDbContract
        : smoke
          ? runHyperdriveMySqlSmoke
          : runHyperdriveMySqlContract;
      console.log(`Starting remote Hyperdrive ${database} ${smoke ? "smoke" : "contract"}`);
      const result = await operation(environment.HYPERDRIVE);
      console.log(`Finished remote Hyperdrive ${database} ${smoke ? "smoke" : "contract"}`);
      return Response.json({ ok: result === "ok" });
    } catch (error) {
      console.error(error);
      return Response.json({ ok: false, error: String(error) }, { status: 500 });
    }
  },
};
