package se.cygni.snake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.WebSocketSession;
import se.cygni.snake.api.event.*;
import se.cygni.snake.api.exception.InvalidPlayerName;
import se.cygni.snake.api.model.*;
import se.cygni.snake.api.response.PlayerRegistered;
import se.cygni.snake.api.util.GameSettingsUtils;
import se.cygni.snake.client.AnsiPrinter;
import se.cygni.snake.client.BaseSnakeClient;
import se.cygni.snake.client.MapCoordinate;
import se.cygni.snake.client.MapUtil;
//import sun.jvm.hotspot.runtime.Thread;

import java.io.*;
import java.util.*;

public class SimpleSnakePlayer extends BaseSnakeClient {

    public class StateObject implements Serializable{
        public boolean[][] seenWorld = new boolean[7][7];
        public boolean dead = false;

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(seenWorld);
        }
    }

    public class QLearner {

        private HashMap<StateObject, float[]> QTable;
        private StateObject prevState;
        private SnakeDirection prevDir;
        private boolean snakeDeath = false;
        public boolean TRAINING = true;
        private boolean readSuccess = false;
        private final float EXPLORE_FACTOR = 0.7f;
        private final float LR = 0.5f;
        private final float DF = 0.8f;

          public QLearner(){

              try {
                  FileInputStream fileIn = new FileInputStream("QTable.ser");
                  ObjectInputStream in = new ObjectInputStream(fileIn);
                  QTable = (HashMap) in.readObject();
                  in.close();
                  fileIn.close();
                  readSuccess = true;
              } catch (IOException i) {
                  i.printStackTrace();
                  QTable = new HashMap<>();
              } catch (ClassNotFoundException c) {
                  System.out.println("QTable class not found");
                  c.printStackTrace();
                  QTable = new HashMap<>();
              }

          }

          private void saveLearning(){
              if(readSuccess) {
                  try {
                      FileOutputStream fileOut = new FileOutputStream("QTable.ser");
                      ObjectOutputStream out = new ObjectOutputStream(fileOut);
                      out.writeObject(QTable);
                      out.close();
                      fileOut.close();
                      System.out.printf("Serialized QLearning is saved in QTable.ser");
                  } catch (IOException i) {
                      i.printStackTrace();
                  }
              }
          }

          public SnakeDirection getMovement(MapUtil mapUtil){

              StateObject state = createStateObject(mapUtil);
              setRewards(state);
              prevState = state;

              return explore(mapUtil);
          }

          private void setRewards(StateObject state){
              int prevDirIndex = getDirIndex(prevDir);
              float[] storedActionRewards = QTable.get(prevState);
              if(storedActionRewards != null){
                  storedActionRewards = new float[4];
                  Arrays.fill(storedActionRewards,0);
              }
              if(state.dead){
                  storedActionRewards[prevDirIndex] = -Float.MAX_VALUE;
              }
              else{
                  int stateReward = 0;
                  for(int x = -1; x<=1; x++){
                      for(int y=-1; y<=1;y++){
                          if(state.seenWorld[3+x][3+y])
                            stateReward--;
                      }
                  }

                  stateReward += 3;
                  storedActionRewards[prevDirIndex] = (1-LR)*storedActionRewards[0] + LR*(stateReward);
                  LOGGER.info("Reward: ", stateReward);
              }

              QTable.put(state, storedActionRewards);
              LOGGER.info("Reward: sdfhsdfhsdfh");

          }


          private SnakeDirection explore(MapUtil mapUtil){
              List<SnakeDirection> directions = new ArrayList<>();

              // Let's see in which directions I can move
              for (SnakeDirection direction : SnakeDirection.values()) {
                  if (mapUtil.canIMoveInDirection(direction)) {
                      directions.add(direction);
                  }
              }

              Random r = new Random();
              SnakeDirection chosenDirection = SnakeDirection.DOWN;

              // Choose a random direction
              if (!directions.isEmpty())
                  chosenDirection = directions.get(r.nextInt(directions.size()));

              return chosenDirection;
          }


          //Creates a storable state object for the QTable / HashMap
          private StateObject createStateObject(MapUtil mapUtil){
              StateObject state = new StateObject();
              MapCoordinate headPos = mapUtil.getMyPosition();
              for(int x = 0; x<7; x++){
                  for(int y = 0; y<7; y++){


                      MapCoordinate checkCoord = new MapCoordinate(headPos.x - 3 + x,
                                                                   headPos.y - 3 + y);

                      if(mapUtil.isTileAvailableForMovementTo(checkCoord)){
                          state.seenWorld[x][y] = true;
                      }
                      else{
                          state.seenWorld[x][y] = false;
                          System.out.print(checkCoord.toString()+ "\n");
                      }
                  }
              }

              return state;
          }

          private int getDirIndex(SnakeDirection dir){
              switch (dir){
                  case LEFT:
                      return 0;
                  case RIGHT:
                      return 1;
                  case UP:
                      return 2;
                  case DOWN:
                      return 3;
                  default:
                      return 0;
              }
          }

          private StateObject predictNewState(StateObject currentState){

              return new StateObject();
          }

    }

    private QLearner snakeQLearner = new QLearner();

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleSnakePlayer.class);

    // Set to false if you want to start the game from a GUI
    private static final boolean AUTO_START_GAME = true;

    // Personalise your game ...
    private static final String SERVER_NAME = "snake.cygni.se";
    private static  final int SERVER_PORT = 80;

    private static final GameMode GAME_MODE = GameMode.TRAINING;
    private static final String SNAKE_NAME = "Hssssss";

    // Set to false if you don't want the game world printed every game tick.
    private static final boolean ANSI_PRINTER_ACTIVE = false;
    private AnsiPrinter ansiPrinter = new AnsiPrinter(ANSI_PRINTER_ACTIVE, true);

    public static void main(String[] args) {
        SimpleSnakePlayer simpleSnakePlayer = new SimpleSnakePlayer();

        try {
            ListenableFuture<WebSocketSession> connect = simpleSnakePlayer.connect();
            connect.get();
        } catch (Exception e) {
            LOGGER.error("Failed to connect to server", e);
            System.exit(1);
        }

        startTheSnake(simpleSnakePlayer);
    }

    /**
     * The Snake client will continue to run ...
     * : in TRAINING mode, until the single game ends.
     * : in TOURNAMENT mode, until the server tells us its all over.
     */
    private static void startTheSnake(final SimpleSnakePlayer simpleSnakePlayer) {
        Runnable task = () -> {
            do {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (simpleSnakePlayer.isPlaying());

            LOGGER.info("Shutting down");
        };

        Thread thread = new Thread(task);
        thread.start();

    }

    @Override
    public void onMapUpdate(MapUpdateEvent mapUpdateEvent) {
        ansiPrinter.printMap(mapUpdateEvent);

        // MapUtil contains lot's of useful methods for querying the map!
        MapUtil mapUtil = new MapUtil(mapUpdateEvent.getMap(), getPlayerId());

        SnakeDirection chosenDirection = snakeQLearner.getMovement(mapUtil);

        // Register action here!
        registerMove(mapUpdateEvent.getGameTick(), chosenDirection);
    }


    @Override
    public void onInvalidPlayerName(InvalidPlayerName invalidPlayerName) {
        LOGGER.debug("InvalidPlayerNameEvent: " + invalidPlayerName);
    }

    @Override
    public void onSnakeDead(SnakeDeadEvent snakeDeadEvent) {
        LOGGER.info("A snake {} died by {}",
                snakeDeadEvent.getPlayerId(),
                snakeDeadEvent.getDeathReason());

        if(snakeDeadEvent.getPlayerId().equals(SNAKE_NAME))
            snakeQLearner.snakeDeath = true;
    }

    @Override
    public void onGameResult(GameResultEvent gameResultEvent) {
        LOGGER.info("Game result:");
        gameResultEvent.getPlayerRanks().forEach(playerRank -> LOGGER.info(playerRank.toString()));
    }

    @Override
    public void onGameEnded(GameEndedEvent gameEndedEvent) {
        LOGGER.debug("GameEndedEvent: " + gameEndedEvent);
        snakeQLearner.saveLearning();
    }

    @Override
    public void onGameStarting(GameStartingEvent gameStartingEvent) {
        LOGGER.debug("GameStartingEvent: " + gameStartingEvent);
    }

    @Override
    public void onPlayerRegistered(PlayerRegistered playerRegistered) {
        LOGGER.info("PlayerRegistered: " + playerRegistered);

        if (AUTO_START_GAME) {
            startGame();
        }
    }

    @Override
    public void onTournamentEnded(TournamentEndedEvent tournamentEndedEvent) {
        LOGGER.info("Tournament has ended, winner playerId: {}", tournamentEndedEvent.getPlayerWinnerId());
        int c = 1;
        for (PlayerPoints pp : tournamentEndedEvent.getGameResult()) {
            LOGGER.info("{}. {} - {} points", c++, pp.getName(), pp.getPoints());
        }
    }

    @Override
    public void onGameLink(GameLinkEvent gameLinkEvent) {
        LOGGER.info("The game can be viewed at: {}", gameLinkEvent.getUrl());
    }

    @Override
    public void onSessionClosed() {
        LOGGER.info("Session closed");
    }

    @Override
    public void onConnected() {
        LOGGER.info("Connected, registering for training...");
        GameSettings gameSettings = GameSettingsUtils.trainingWorld();
        registerForGame(gameSettings);
    }

    @Override
    public String getName() {
        return SNAKE_NAME;
    }

    @Override
    public String getServerHost() {
        return SERVER_NAME;
    }

    @Override
    public int getServerPort() {
        return SERVER_PORT;
    }

    @Override
    public GameMode getGameMode() {
        return GAME_MODE;
    }
}
