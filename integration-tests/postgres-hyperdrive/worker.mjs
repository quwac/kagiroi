import { runHyperdrivePostgresContract } from "./bridge.mjs";

export default {
  async fetch(request, environment) {
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", { status: 405 });
    }
    try {
      console.log("Starting remote Hyperdrive contract");
      const result = await runHyperdrivePostgresContract(environment.HYPERDRIVE);
      console.log("Finished remote Hyperdrive contract");
      return Response.json({ ok: result === "ok" });
    } catch (error) {
      console.error(error);
      return Response.json({ ok: false, error: String(error) }, { status: 500 });
    }
  },
};
