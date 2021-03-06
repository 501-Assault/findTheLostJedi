package tiefighter;

import agents.LARVAFirstAgent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import swing.LARVADash;

import java.util.ArrayList;

public class MyFirstTieFighter extends LARVAFirstAgent {

    /**** ATTRIBUTES ****/
    String service = "PManager", problemManager = "", content, sessionKey, sessionManager, storeManager, sensorKeys;
    ACLMessage open, session;
    String[] contentTokens;
    boolean step = true;

    // Agent status
    enum Status {
        CHECKIN, CHECKOUT, OPENPROBLEM, COMISSIONING,
        JOINSESSION, SOLVEPROBLEM, CLOSEPROBLEM, EXIT
    }
    Status mystatus;

    // Problem worlds
    final String WORLDS[] = {
            "Dagobah", "Abafar", "Batuu", "Chandrila", "Dathomir", "Endor",
            "Felucia", "Hoth", "Mandalore", "Tatooine", "Wobani", "Zeffo"
    };
        //-- Preselected problem
    String problem = WORLDS[1];

    // World sizes
    int width, height, maxFlight;

    // Sensors information
        //-- Integrated into the TieFighter
    String[] mySensors = new String[] { "GPS", "DISTANCE", "ANGULAR", "LIDAR", "THERMAL" };
    double gps[], distance, angular;
    int lidar[][], thermal[][];
    final int AGENT_IN_LIDAR_X = 5, AGENT_IN_LIDAR_Y = 5;
    final int AGENT_IN_THERMAL_X = 5, AGENT_IN_THERMAL_Y = 5;
        //-- Calculated
    double energy = 3500.0;
    int orientation = 0;
    Position3D currentPosition = new Position3D(-1, -1, -1);
    Position3D targetPosition = new Position3D(-1, -1, -1);

    // Memory
    String lastAction = "";
    boolean avoidCrash = false;
    boolean moveToVisitPosition = false;
    ArrayList<Position3D> route = new ArrayList<Position3D>();
    ArrayList<Position3D> prohibitedRoute = new ArrayList<Position3D>();

    /**** METHODS ****/
    // Up, Execute, Down Agent
    @Override
    public void setup() {
        super.setup();
        logger.onOverwrite();
        logger.setLoggerFileName("mylog.json");
        Info("Setup and configure agent");
        mystatus = Status.CHECKIN;
        exit = false;
        this.myDashboard = new LARVADash(this);
        this.doActivateLARVADash();
    }

    @Override
    public void Execute() {
        Info("Status: " + mystatus.name());
        if (step) {
            step = this.Confirm("The next status will be " + mystatus.name() + "\n\nWould you like to continue step by step?");
        }
        switch (mystatus) {
            case CHECKIN:
                mystatus = MyCheckin();
                break;
            case OPENPROBLEM:
                mystatus = MyOpenProblem();
                break;
            case COMISSIONING:
                mystatus = MyComissioning();
                break;
            case JOINSESSION:
                mystatus = MyJoinSession();
                break;
            case SOLVEPROBLEM:
                mystatus = MySolveProblem();
                break;
            case CLOSEPROBLEM:
                mystatus = MyCloseProblem();
                break;
            case CHECKOUT:
                mystatus = MyCheckout();
                break;
            case EXIT:
            default:
                exit = true;
                break;
        }
    }

    @Override
    public void takeDown() {
        Info("Taking down and deleting agent");
        this.saveSequenceDiagram("./" + this.problem + ".seqd");
        super.takeDown();
    }

    // Status communication
    public Status MyCheckin() {
        Info("Loading passport and checking-in to LARVA");
        if (!loadMyPassport("passport/MyPassport.passport")) {
            Error("Unable to load passport file");
            return Status.EXIT;
        }
        if (!doLARVACheckin()) {
            Error("Unable to checkin");
            return Status.EXIT;
        }
        return Status.OPENPROBLEM;
    }

    public Status MyOpenProblem() {
        if (this.DFGetAllProvidersOf(service).isEmpty()) {
            Error("Service PMANAGER is down");
            return Status.CHECKOUT;
        }
        problemManager = this.DFGetAllProvidersOf(service).get(0);
        Info("Found problem manager " + problemManager);
        this.outbox = new ACLMessage();
        outbox.setSender(getAID());
        outbox.addReceiver(new AID(problemManager, AID.ISLOCALNAME));

        problem = inputSelect("Select world", WORLDS, problem);

        outbox.setContent("Request open " + problem);
        this.LARVAsend(outbox);
        Info("Request opening problem " + problem + " to " + problemManager);
        open = LARVAblockingReceive();
        Info(problemManager + " says: " + open.getContent());
        content = open.getContent();
        contentTokens = content.split(" ");
        if (contentTokens[0].toUpperCase().equals("AGREE")) {
            sessionKey = contentTokens[4];
            session = LARVAblockingReceive();
            sessionManager = session.getSender().getLocalName();
            Info(sessionManager + " says: " + session.getContent());
            return Status.COMISSIONING;
        } else {
            Error(content);
            return Status.CHECKOUT;
        }
    }

    public Status MyCloseProblem() {
        outbox = open.createReply();
        outbox.setContent("Cancel session " + sessionKey);
        Info("Closing problem Helloworld, session " + sessionKey);
        this.LARVAsend(outbox);
        inbox = LARVAblockingReceive();
        Info(problemManager + " says: " + inbox.getContent());
        return Status.CHECKOUT;
    }

    public Status MyCheckout() {
        this.doLARVACheckout();
        return Status.EXIT;
    }

    public Status MyComissioning() {
        String localService = "STORE " + sessionKey;

        if (this.DFGetAllProvidersOf(localService).isEmpty()) {
            Error("Service STORE is down");
            return Status.CLOSEPROBLEM;
        }

        storeManager = this.DFGetAllProvidersOf(localService).get(0);
        Info("Found store manager " + storeManager);
        sensorKeys = "";
        for (String sensor: mySensors) {
            outbox = new ACLMessage();
            outbox.setSender(this.getAID());
            outbox.addReceiver(new AID(storeManager, AID.ISLOCALNAME));
            outbox.setContent("Request product " + sensor + " session " + sessionKey);

            this.LARVAsend(outbox);
            inbox = this.LARVAblockingReceive();

            if (inbox.getContent().startsWith("Confirm")) {
                Info("Bought sensor " + sensor);
                sensorKeys += inbox.getContent().split(" ")[2] + " ";
            }
            else {
                this.Alert("Sensor " + sensor + " could not be obtained");
                return Status.CLOSEPROBLEM;
            }
        }
        Info("Bought all sensor keys " + sensorKeys);
        return Status.JOINSESSION;
    }

    public Status MyJoinSession() {
        session = session.createReply();
        session.setContent("Request join session " + sessionKey + " attach sensors " + sensorKeys);

        this.LARVAsend(session);
        session = this.LARVAblockingReceive();

        String parse[] = session.getContent().split(" ");
        if (parse[0].equals("Confirm")) {
            width = Integer.parseInt(parse[8]);
            height = Integer.parseInt(parse[10]);
            maxFlight = Integer.parseInt(parse[14]);
            return Status.SOLVEPROBLEM;
        }
        else {
            Alert("Error joining session: " + session.getContent());
            return Status.CLOSEPROBLEM;
        }
    }

    public Status MySolveProblem() {
        boolean jediCaptured = false;
        boolean jediReachable = true;
        boolean firstTime = true;
        ArrayList<String> actions = new ArrayList<String>();

        while(!jediCaptured && jediReachable) {
            if (!readSensors()) { return Status.CLOSEPROBLEM; }

            if (firstTime) {
                setCurrentPositionToSensorsValue();
                setTargetPosition();
                actions.add("RECHARGE");
                actions.addAll(goToMaximumAltitude());
                firstTime = false;
            }
            else if (isOnTheGround() && isLastAction("RECHARGE") && !isFlyingAtMaximumAltitude()) {
                actions.addAll(goToMaximumAltitude());
            }
            else {
                actions.addAll(decideActions());
            }

            for (String action : actions) {
                if (!doAction(action)) { return Status.CLOSEPROBLEM; }
                jediCaptured = action == "CAPTURE";
                jediReachable = action != "UNREACHABLE";
            }

            actions.clear();
        }

        if (!readSensors()) { return Status.CLOSEPROBLEM; }
        if (!jediReachable) {
            Info("||---- Tatooine cannot be solved. The jedi is unreachable ----||\n");
        }

        return Status.CLOSEPROBLEM;
    }

    // World information (Some getters and setters)
        //-- World sizes
    public int getWorldWidth() { return width; }
    public int getWorldHeight() { return height; }
    public int getWorldMaxFlight() { return maxFlight; }
    public int getWorldMaxFlightAltitude() { return (getWorldMaxFlight() / 5) * 5; }
        //-- Altitude
    public double getAltitude() { return gps[2]; }
        //-- Energy
    public double getEnergy() { return energy; }
    public void reduceEnergy(int energyPoints) { energy -= energyPoints; }
    public void fillEnergy() { energy = 3500.0; }
        //-- Orientation
    public int getOrientation() { return orientation; }
    public void setOrientation(int newOrientation) { orientation = newOrientation; }
    public int getAngularOrientation() { // Traduce los rangos continuos del angular a los rangos discretos del orientation
        if((337.5 < angular && angular <= 360) || (0 < angular && angular <= 22.5)) {
            return 0;
        }
        else if (22.5 < angular && angular <= 67.5) {
            return 45;
        }
        else if (67.5 < angular && angular <= 112.5) {
            return 90;
        }
        else if (112.5 < angular && angular <= 157.5) {
            return 135;
        }
        else if (157.5 < angular && angular <= 202.5) {
            return 180;
        }
        else if (202.5 < angular && angular <= 247.5 ) {
            return 225;
        }
        else if (247.5 < angular && angular <= 292.5) {
            return 270;
        }
        else /*if (292.5 < angular && angular <= 337.5)*/ {
            return 315;
        }
    }
    public String getDirectionFromOrientation(int orientation) {
        String direction;
        switch (orientation) {
            case 0:
                direction = "E";
                break;
            case 45:
                direction = "NE";
                break;
            case 90:
                direction = "N";
                break;
            case 135:
                direction = "NW";
                break;
            case 180:
                direction = "W";
                break;
            case 225:
                direction = "SW";
                break;
            case 270:
                direction = "S";
                break;
            case 315:
                direction = "SE";
                break;
            default:
                direction = "NONE";
                break;
        }
        return direction;
    }
    public int getOrientationFromDirection(String direction) {
        int newOrientation;
        switch (direction) {
            case "E":
                newOrientation = 0;
                break;
            case "NE":
                newOrientation = 45;
                break;
            case "N":
                newOrientation = 90;
                break;
            case "NW":
                newOrientation = 135;
                break;
            case "W":
                newOrientation = 180;
                break;
            case "SW":
                newOrientation = 225;
                break;
            case "S":
                newOrientation = 270;
                break;
            case "SE":
                newOrientation = 315;
                break;
            default:
                newOrientation = -1;
                break;
        }
        return newOrientation;
    }
        //-- Position
    public void setCurrentPositionToSensorsValue() {
            currentPosition.setX(gps[0]);
            currentPosition.setY(gps[1]);
            currentPosition.setZ(gps[2]);
            route.add(new Position3D(currentPosition.getX(), currentPosition.getY(), currentPosition.getZ()));
        }
    public void updateCurrentPosition(String action) {
        switch (action) {
            case "UP":
                currentPosition.setZ(currentPosition.getZ()+5);
                break;
            case "DOWN":
                currentPosition.setZ(currentPosition.getZ()-5);
                break;
            case "MOVE":
                currentPosition = nextPositionIfMove(getOrientation());
                break;
        }
    }
        //-- Target Position
    public void setTargetPosition() {
        targetPosition.setX(currentPosition.getX() + distance * Math.cos(Math.toRadians(angular)));
        targetPosition.setY(currentPosition.getY() - distance * Math.sin(Math.toRadians(angular)));
    }

    //-- Last action
    public String getLastAction() { return lastAction; }
    public void setLastAction(String action) { lastAction = action; }

    // Read sensors communication
    public boolean readSensors() {
        session = session.createReply();
        session.setContent("Query sensors session " + sessionKey);

        this.LARVAsend(session);
        session = this.LARVAblockingReceive();

        if (session.getContent().startsWith("Refuse") || session.getContent().startsWith("Failure")) {
            Alert("Reading of sensors failed due to " + session.getContent());
            return false;
        }

        reduceEnergy(mySensors.length);

        getSensorsInfo();
        showSensorsInfo();

        setCurrentPositionToSensorsValue();

        return true;
    }
        //-- Sensors information from myDashboard
    public void getSensorsInfo() {
        gps = myDashboard.getGPS();
        angular = myDashboard.getAngular();
        distance = myDashboard.getDistance();
        lidar = myDashboard.getLidar();
        thermal = myDashboard.getThermal();
    }
    // Display sensors information
        //-- Integrated sensors
    public void showSensorsInfo() {
        Info("Reading of GPS= \tX=" + gps[0] + " Y=" + gps[1] + " Z=" + gps[2]);
        Info("Reading of angular= " + angular + "??");
        Info("Reading of distance= " + distance + "m");
        String message = "Reading of sensor Lidar;\n";
        for (int x = 0; x < lidar.length; x++) {
            for (int y = 0; y < lidar[0].length; y++) {
                if (-2147483648 == lidar[x][y])
                    message += String.format("---{%d, %d}\t", x, y);
                else
                    message += String.format("%03d{%d, %d}\t", lidar[x][y], x, y);
            }
            message += "\n";
        }
        Info(message);
        message = "Reading of sensor Thermal;\n";
        for (int x = 0; x < thermal.length; x++) {
            for (int y = 0; y < thermal[0].length; y++)
                    message += String.format("%03d{%d, %d}\t", thermal[x][y], x, y);
            message += "\n";
        }
        Info(message);
    }
        //-- Calculated sensor information
    public void showAgentInfo() {
        Info("Agent position= \tX=" + currentPosition.getX() + " Y=" + currentPosition.getY() + " Z=" + currentPosition.getZ());
        Info("Agent orientation= " + getOrientation() + "??");
        Info("Agent energy= " + getEnergy());
        Info("Target 2D position= \tX=" + targetPosition.getX() + " Y=" + targetPosition.getY());
        Info("Current distance to target= " + getDistanceToTarget(currentPosition.getX(), currentPosition.getY()));
    }

    // Single actions
    public String turnLeft() {
        setOrientation((getOrientation() + 45) % 360);
        return "LEFT";
    }
    public String turnRight() {
        setOrientation(Math.floorMod(getOrientation() - 45, 360));
        return "RIGHT";
    }

    // Plans
    public ArrayList<String> goToGround() {
        ArrayList<String> actions = new ArrayList<String>();

        int numberOfDowns = lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] / 5;
        for (int i = 0; i < numberOfDowns; i++)
            actions.add("DOWN");

        return actions;
    }

    public ArrayList<String> goToMaximumAltitude() {
        ArrayList<String> actions = new ArrayList<String>();

        int numberOfUps = (getWorldMaxFlight() - (int) getAltitude()) / 5;
        for (int i = 0; i < numberOfUps; i++)
            actions.add("UP");

        return actions;
    }

    public ArrayList<String> rechargeBattery() {
        ArrayList<String> actions = new ArrayList<String>();

        actions.addAll(goToGround());
        actions.add("RECHARGE");

        return actions;
    }

    public ArrayList<String> decideActions() {
        ArrayList<String> actions = new ArrayList<String>();

        if (isOnTarget()) {
            actions.add("CAPTURE");
            return actions;
        }
        else if (isAboveTarget()) {
            actions.addAll(goToGround());
            actions.add("CAPTURE");
            return actions;
        }
        else if (isNecessaryToRecharge() || isInterestingToRecharge()) {
            actions.addAll(rechargeBattery());
            return actions;
        }
        else {
            actions.addAll(goToBestPosition());
            return actions;
        }
    }

    public ArrayList<String> goToBestPosition() {
        ArrayList<String> actions = new ArrayList<String>();
        int Ax = 0, Ay = 0;
        int minimumThermal = -1;
        double shortestDistance = -1.0;
        boolean first = true;

        // Check if jedi is reachable
        for (int i = 0; i < thermal.length; i++) {
            for (int j = 0; j < thermal[0].length; j++)
                if (thermal[i][j] == 0 && lidar[i][j] < 0 ) {
                    actions.add("UNREACHABLE");
                    return actions;
                }
        }

        if (isMovePossible(getOrientation()) && isInRightOrientation()) {
            String direction = getDirectionFromOrientation(getOrientation());
            actions.addAll(goToDirection(direction));
        }
        else {
            for (int x = -1; x <= 1 && minimumThermal != 0; x++) {
                for (int y = -1; y <= 1 && minimumThermal != 0; y++) {
                    if (lidar[AGENT_IN_LIDAR_X + x][AGENT_IN_LIDAR_Y + y] >= 0) {
                        if (!(x == 0 && y == 0)) {
                            double distanceFromPosition = getDistanceToTarget(currentPosition.getX() + y, currentPosition.getY() + x);
                            int thermalValue = thermal[AGENT_IN_THERMAL_X + x][AGENT_IN_THERMAL_Y + y];
                            if (!nextPositionHasBeenVisited(x, y, 1000) || moveToVisitPosition) {
                                if (first) {
                                    minimumThermal = thermalValue;
                                    shortestDistance = distanceFromPosition;
                                    Ax = x; Ay = y;
                                    first = false;
                                }
                                else if (minimumThermal > thermalValue && distance < 5.0) {
                                    minimumThermal = thermalValue;
                                    Ax = x; Ay = y;
                                }
                                else if (shortestDistance > distanceFromPosition) {
                                    shortestDistance = distanceFromPosition;
                                    Ax = x; Ay = y;
                                }
                            }
                        }
                    }
                    else if (!isNecessaryToAvoidCrash()){
                        if (!isMovePossible(getAngularOrientation())) {
                            avoidCrash = true;
                            route.clear();
                        }
                    }
                }
            }

            if (Ax == -1 && Ay == -1) { actions.addAll(goToDirection("NW")); }
            else if (Ax == -1 && Ay == 0) { actions.addAll(goToDirection("N")); }
            else if (Ax == -1 && Ay == 1) { actions.addAll(goToDirection("NE")); }
            else if (Ax == 0 && Ay == -1) { actions.addAll(goToDirection("W")); }
            else if (Ax == 0 && Ay == 1) { actions.addAll(goToDirection("E")); }
            else if (Ax == 1 && Ay == -1) { actions.addAll(goToDirection("SW")); }
            else if (Ax == 1 && Ay == 0) { actions.addAll(goToDirection("S")); }
            else if (Ax == 1 && Ay == 1) { actions.addAll(goToDirection("SE")); }
            else if (Ax == 0 && Ay == 0) { actions.addAll(goToDirection("NONE"));
                /*final int BACK_STEPS = 3;
                actions = goToBackPosition(BACK_STEPS);
                for(int i = 0; i < BACK_STEPS; i++) {
                    prohibitedRoute.add(route.remove(route.size()-1-i));
                }*/
            }
        }

        return actions;
    }

    public ArrayList<String> goToBackPosition(int steps) {
        ArrayList<String> actions = new ArrayList<String>();

        for (int i = 0; i < steps; i++) {
            Position3D actualPosition = route.get(route.size()-1-i);
            double actualPositionX = actualPosition.getX();
            double actualPositionY = actualPosition.getY();
            Position3D nextPosition = route.get(route.size()-2-i);
            double nextPositionX = nextPosition.getX();
            double nextPositionY = nextPosition.getY();
            String directionOfNextPosition = "NONE";
            if (nextPositionY == actualPositionY - 1 && nextPositionX == actualPositionX - 1) {
                directionOfNextPosition = "NW";
            }
            else if (nextPositionY == actualPositionY - 1 && nextPositionX == actualPositionX) {
                directionOfNextPosition = "N";
            }
            else if (nextPositionY == actualPositionY - 1 && nextPositionX == actualPositionX + 1) {
                directionOfNextPosition = "NE";
            }
            else if (nextPositionY == actualPositionY && nextPositionX == actualPositionX - 1) {
                directionOfNextPosition = "W";
            }
            else if (nextPositionY == actualPositionY && nextPositionX == actualPositionX + 1) {
                directionOfNextPosition = "E";
            }
            else if (nextPositionY == actualPositionY + 1 && nextPositionX == actualPositionX - 1) {
                directionOfNextPosition = "SW";
            }
            else if (nextPositionY == actualPositionY + 1 && nextPositionX == actualPositionX) {
                directionOfNextPosition = "S";
            }
            else if (nextPositionY == actualPositionY + 1 && nextPositionX == actualPositionX + 1) {
                directionOfNextPosition = "SE";
            }
            actions.addAll(goToDirection(directionOfNextPosition));
        }

        return actions;
    }

    public ArrayList<String> goToDirection(String direction) {
        ArrayList<String> actions = new ArrayList<String>();

        int newOrientation = getOrientationFromDirection(direction);

        while (this.orientation != newOrientation && newOrientation != -1)
            actions.add(turnLeft());

        if (newOrientation != -1) {
            actions.add("MOVE");
        }

        return actions;
    }

    public boolean doAction(String action) {
        if (action == "UNREACHABLE") {
            return true;
        }
        else {
            session = session.createReply();
            session.setContent("Request execute " + action + " session " + sessionKey);

            this.LARVAsend(session);
            session = this.blockingReceive();

            if (session.getContent().endsWith("Success") || session.getContent().endsWith("Success\nCaptured Luke")) {
                Info("Action " + action + " executed successfully");
            }
            else {
                return false;
            }
        }

        if (action == "RECHARGE") { fillEnergy(); }
        else if (action != "CAPTURE") { reduceEnergy(1); }

        showAgentInfo();

        updateCurrentPosition(action);

        setLastAction(action);

        return true;
    }

    // Utilities
    public double getDistanceToTarget(double fromPositionX, double fromPositionY) {
        return Math.sqrt(Math.pow(fromPositionX - targetPosition.getX(), 2) + Math.pow(fromPositionY - targetPosition.getY(), 2));
    }

    public boolean isOnTheGround() { return lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] == 0; }

    public boolean isAboveTarget() { return distance == 0 && lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] > 0; }

    public boolean isOnTarget() { return distance == 0 && lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] == 0; }

    private boolean isInRightOrientation() {
        return orientation == getAngularOrientation();
    }

    public boolean isFlyingAtMaximumAltitude() {
        return getAltitude() == getWorldMaxFlightAltitude();
    }
    
    public boolean isLastAction(String action) { return getLastAction() == action;   }

    public boolean isNecessaryToRecharge() { return getEnergy() <= (getWorldMaxFlight() / 5) * 2; }

    public boolean isInterestingToRecharge() { return getEnergy() < 800 && lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] <= 55; }

    public boolean isMovePossible(int supposedOrientation) {
        int x = 5, y = 5;
        switch (supposedOrientation) {
            case 0: // Este [10][11]
                y += 1;
                break;
            case 45: // Noreste [9][11]
                x -= 1;
                y += 1;
                break;
            case 90: // Norte [9][10]
                x -= 1;
                break;
            case 135: // Noroeste [9][9]
                x -= 1;
                y -= 1;
                break;
            case 180: // Oeste [10][9]
                y -= 1;
                break;
            case 225: // Suroeste [11][9]
                x += 1;
                y -= 1;
                break;
            case 270: // Sur [11][10]
                x += 1;
                break;
            case 315: // Sureste [11][11]
                x += 1;
                y += 1;
                break;
        }
        return lidar[x][y] >= 0;
    }

    public boolean isNecessaryToAvoidCrash() { return avoidCrash; }

    public boolean isCloseToBoundaries() {
        final int DISTANCE_TO_BE_CLOSE = 4;
        boolean isCloseToNorth = gps[1] <= DISTANCE_TO_BE_CLOSE;
        boolean isCloseToWest = gps[0] <= DISTANCE_TO_BE_CLOSE;
        boolean isCloseToSouth = gps[1] + DISTANCE_TO_BE_CLOSE >= getWorldHeight();
        boolean isCloseToEast = gps[0] + DISTANCE_TO_BE_CLOSE >= getWorldWidth();
        return isCloseToNorth || isCloseToWest || isCloseToSouth || isCloseToEast;
    }

    public boolean nextPositionIsPreviousPosition(int supposedOrientation) {
        Position3D nextPosition = nextPositionIfMove(supposedOrientation);
        return route.get(route.size()-1).isEqualTo2D(nextPosition.getX(), nextPosition.getY());
    }

    public Position3D nextPositionIfMove(int supposedOrientation) {
        double x = currentPosition.getX(), y = currentPosition.getY(), z = currentPosition.getZ();
        switch (supposedOrientation) {
            case 0:
                x += 1;
                break;
            case 45:
                y -= 1;
                x += 1;
                break;
            case 90:
                y -= 1;
                break;
            case 135:
                y -= 1;
                x -= 1;
                break;
            case 180:
                x -= 1;
                break;
            case 225:
                y += 1;
                x -= 1;
                break;
            case 270:
                y += 1;
                break;
            case 315:
                y += 1;
                x += 1;
                break;
        }
        return new Position3D(x, y, z);
    }

    public boolean nextPositionHasBeenVisited(int x, int y, int scope) {
        if (route.size() < scope)
            scope = route.size();

        boolean visited = false;
        for (int i = route.size() - 1; i >= route.size() - scope && !visited; i--)
            visited = route.get(i).isEqualTo2D(currentPosition.getX() + y, currentPosition.getY() + x);

        for (int i = 0; i < prohibitedRoute.size()-1 && !visited; i++)
            visited = prohibitedRoute.get(i).isEqualTo2D(currentPosition.getX() + y, currentPosition.getY() + x);

        return visited;
    }
}