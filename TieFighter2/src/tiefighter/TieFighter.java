
package tiefighter;

import appboot.LARVABoot;


public class TieFighter {
    public static void main(String[] args) {
        LARVABoot connection = new LARVABoot();

        String host = "";
        host = connection.inputSelect("Select connection server", new String[]{"localhost", "isg2.ugr.es"}, host);

        if (host != null && host.length() > 0) {
            connection.Boot(host, 1099);
        }

        connection.launchAgent("CLONE501", MyFirstTieFighter.class);
        connection.WaitToShutDown();
    }
}
