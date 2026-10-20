import { Container } from "@cloudflare/containers";
import { forwardToJava } from "./forward.js";

export class RecordOnContainer extends Container {
  defaultPort = 10000;
  sleepAfter = "2m";
  enableInternet = false;
  envVars = {
    SERVER_ADDRESS: "0.0.0.0",
    PORT: "10000",
    DEBUG: "false"
  };
}

export default {
  fetch(request, env) {
    return forwardToJava(request, env.RECORD_ON);
  }
};
