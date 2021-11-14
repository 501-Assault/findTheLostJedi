package tiefighter;

import agents.LARVAFirstAgent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import swing.LARVADash;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MyFirstTieFighter extends LARVAFirstAgent{

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
            "Abafar", "Batuu", "Chandrila", "Dathomir", "Endor",
            "Felucia", "Hoth", "Mandalore", "Tatooine", "Wobani"
    };
        //-- Preselected problem
    String problem = WORLDS[0];

    // World sizes
    int width, height, maxFlight;

    // Sensors information
        //-- Integrated into the TieFighter
    String[] mySensors = new String[] { "GPS", "DISTANCE", "ANGULAR", "LIDAR" };
    double gps[], distance, angular;
    int lidar[][];
    final int AGENT_IN_LIDAR_X = 5, AGENT_IN_LIDAR_Y = 5;
        //-- Calculated
    double energy = 3500.0;
    int orientation = 0;
    Position3D position3D = new Position3D(-1, -1, -1);

    // Memory
    String lastAction = "";
    boolean avoidCrash = false;
    boolean moveState = true;
    ArrayList<Position3D> route = new ArrayList<Position3D>();
    
    List<Integer> list = new ArrayList<Integer>();
    ArrayList<List<Integer>> directions = new ArrayList<List<Integer>>();
    
    int closestOrientation = 360;

    /**** METHODS ****/
    // Up, Execute, Down Agent
    @Override
    public void setup() {
        super.setup();
        logger.onOverwrite();
        logger.setLoggerFileName("mylog.json");
        this.enableDeepLARVAMonitoring();
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
        boolean firstTime = true;
        ArrayList<String> actions = new ArrayList<String>();

        while(!jediCaptured) {
            if (!readSensors()) { return Status.CLOSEPROBLEM; }

            if (firstTime) {
                setPosition3DToSensorsValue();
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
                jediCaptured = action == "CAPTURED";
            }

            actions.clear();
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
        //-- Position
    public void setPosition3DToSensorsValue() {
            position3D.setX(gps[0]);
            position3D.setY(gps[1]);
            position3D.setZ(gps[2]);
        }
    public void updatePosition3D(String action) {
        switch (action) {
            case "UP":
                position3D.setZ(position3D.getZ()+5);
                break;
            case "DOWN":
                position3D.setZ(position3D.getZ()-5);
                break;
            case "MOVE":
                position3D = nextPositionIfMove(getOrientation());
                break;
        }
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
        return true;
    }
        //-- Sensors information from myDashboard
    public void getSensorsInfo() {
        gps = myDashboard.getGPS();
        angular = myDashboard.getAngular();
        distance = myDashboard.getDistance();
        lidar = myDashboard.getLidar();
    }
    // Display sensors information
        //-- Integrated sensors
    public void showSensorsInfo() {
        Info("Reading of GPS\nX=" + gps[0] + " Y=" + gps[1] + " Z=" + gps[2]);
        Info("Reading of angular= " + angular + "º");
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
    }
        //-- Calculated sensor information
    public void showAgentInfo() {
        Info("Agent position= \nX=" + position3D.getX() + " Y=" + position3D.getY() + " Z=" + position3D.getZ());
        Info("Agent orientation= " + getOrientation() + "º");
        Info("Agent energy= " + getEnergy());
    }

    public void changeMoveState() { moveState = !moveState; }

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
            
            if(!isInRightOrientation()){
                double angularDistance1 = angular - getOrientation();
                double angularDistance2 = 360 - angularDistance1;
                if (angularDistance1 >= 0) {
                    if (angularDistance1 <= angularDistance2) { actions.add(turnLeft()); }
                    else { actions.add(turnRight()); }
                }
                else {
                    if (Math.abs(angularDistance1) <= angularDistance2) { actions.add(turnRight()); }
                    else { actions.add(turnLeft()); }
                }
            }
                
            readDirections();
            actions.addAll(possibleRoad());
            
            return actions;
        }
        /*
        else if (!isNecessaryToAvoidCrash()) { // Si no hay que esquivar
            if (isInRightOrientation()) { // Si está en la dirección del objetivo | Intenta avanzar
                if(isMovePossible(getOrientation())) { actions.add("MOVE"); }
                else { avoidCrash = true; } // Si hay un obstáculo delante
            }
            else { // Si no está en la dirección del objetivo | Gira
                double angularDistance1 = angular - getOrientation();
                double angularDistance2 = 360 - angularDistance1;
                if (angularDistance1 >= 0) {
                    if (angularDistance1 <= angularDistance2) { actions.add(turnLeft()); }
                    else { actions.add(turnRight()); }
                }
                else {
                    if (Math.abs(angularDistance1) <= angularDistance2) { actions.add(turnRight()); }
                    else { actions.add(turnLeft()); }
                }
            }
            return actions;
        }
        else  { // Si hay que esquivar
            if (isMovePossible(getAngularOrientation())) { // Si es posible moverme hacia donde está el objetivo termino de esquivar
                avoidCrash = false;
                while(getOrientation() != getAngularOrientation()) {
                    if (moveState) actions.add(turnRight());
                    else actions.add(turnLeft());
                }
                return actions;
            }
            else if (isMovePossible(getOrientation())) {
                actions.add("MOVE");
                return actions;
            }
            else {
                if (moveState) actions.add(turnLeft());
                else actions.add(turnRight());
                return actions;
            }
        }*/
    }

    public boolean doAction(String action) {
        session = session.createReply();
        session.setContent("Request execute " + action + " session " + sessionKey);

        this.LARVAsend(session);
        session = this.blockingReceive();

        if (session.getContent().endsWith("Success")) {
            Info("Action " + action + " executed successfully");
        }
        else {
            return false;
        }

        if (action == "RECHARGE") { fillEnergy(); }
        else if (action != "CAPTURE") { reduceEnergy(1); }

        showAgentInfo();

        if (action == "MOVE") {
            boolean in = true;
            for (Position3D position : route) {
                if (position.isEqualTo2D(position3D.getX(), position3D.getY()) && in) {
                    if (isCloseToBoundaries())
                        changeMoveState();
                    in = false;
                };
            }
            route.add(new Position3D(position3D.getX(), position3D.getY(), position3D.getZ()));
        }

        updatePosition3D(action);
        setLastAction(action);

        return true;
    }

    // Condition checks
    public boolean isOnTheGround() { return lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] == 0; }

    public boolean isAboveTarget() { return distance == 0 && lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] > 0; }

    public boolean isOnTarget() { return distance == 0 && lidar[AGENT_IN_LIDAR_X][AGENT_IN_LIDAR_Y] == 0; }

    private boolean isInRightOrientation() {
        return getOrientation() - 22.5 <= angular && angular <= getOrientation() + 22.5;
    }

    public boolean isFlyingAtMaximumAltitude() {
        return getAltitude() == getWorldMaxFlightAltitude();
    }
    
    public boolean isLastAction(String action) { return lastAction == action;   }

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
        double x = position3D.getX(), y = position3D.getY(), z = position3D.getZ();
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
    
    public ArrayList<String> possibleRoad(){
        ArrayList<String> actions = new ArrayList<String>();
          
        for(int i=0; i<directions.size(); i++){    
            if(!directions.get(i).isEmpty()){
                if((Math.abs(directions.get(i).get(0) - getAngularOrientation()))% 180 < closestOrientation){
                    closestOrientation = directions.get(i).get(0);
                }
            }
        }  
        
        if(getOrientation() == closestOrientation){
            actions.add("MOVE");
        }
        else{
            //while(getOrientation() != closestOrientation){
            //actions.add(turnLeft());
            
        }
        
        return actions;
    }
    
    public void readDirections(){
        int orientation_now = 0;
        int x=5, y=5;
        
        for(int i=0,j=0; i<360 && j<8; i+=45,j++){
            
            list.add(i);
            directions.add(list);
            
            while(x<AGENT_IN_LIDAR_X+5 && y<AGENT_IN_LIDAR_Y+5){
                
                switch (orientation_now) {
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
                
                if(lidar[x][y] < 0){
                    directions.get(j).clear();
                    break;
                }
                else{
                    directions.get(j).add(lidar[x][y]);
                }
                
            }
            
            list.clear();
        }     
    }
}