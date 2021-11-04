package deathstar;

import agents.DeathStar;
import appboot.LARVABoot;
import static crypto.Keygen.getHexaKey;

public class MyDeathStar {

    public static void main(String[] args) {
        LARVABoot connection = new LARVABoot();

        connection.Boot("isg2.ugr.es",1099);
        connection.launchAgent("DEATHSTAR "+getHexaKey(8), DeathStar.class);
        connection.WaitToClose().ShutDown();
//                connection.WaitToShutDown();

    }

}
