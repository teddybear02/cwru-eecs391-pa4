package edu.cwru.sepia.agent;

import edu.cwru.sepia.action.*;
import edu.cwru.sepia.action.Action;
import edu.cwru.sepia.action.ActionResult;
import edu.cwru.sepia.environment.model.history.DamageLog;
import edu.cwru.sepia.environment.model.history.DeathLog;
import edu.cwru.sepia.environment.model.history.History;
import edu.cwru.sepia.environment.model.state.State;
import edu.cwru.sepia.environment.model.state.Unit;

import java.io.*;
import java.util.*;

import static edu.cwru.sepia.util.DistanceMetrics.chebyshevDistance;

/**
 * This class represents a reinforcement learning agent which will learn how to win with enough practice.
 */
public class RLAgent extends Agent {

    /**
     * Set in the constructor. Defines how many learning episodes your agent should run for.
     * When starting an episode. If the count is greater than this value print a message
     * and call sys.exit(0.0)
     */
    public final int numEpisodes;

    /**
     * List of your footmen and your enemies footmen
     */
    private List<Integer> myFootmen;
    private List<Integer> enemyFootmen;
    private boolean frozen = false;
    private double totalQ = 0.0;
    private int learningEpisodes = 0;
    private int testingEpisodes = 0;
    private double averageReward;
    private List<Double> averageRewards;
    private int episodeNumber;

    /**
     * Convenience variable specifying enemy agent number. Use this whenever referring
     * to the enemy agent. We will make sure it is set to the proper number when testing your code.
     */
    public static final int ENEMY_PLAYERNUM = 1;

    /**
     * Set this to whatever size your feature vector is.
     */
    public static final int NUM_FEATURES = 5;
    private Map<Integer, Double> rewards;

    /** Use this random number generator for your epsilon exploration. When you submit we will
     * change this seed so make sure that your agent works for more than the default seed.
     */
    public final Random random = new Random(12345);

    /**
     * Your Q-function weights.
     */
    public Double[] weights;

    /**
     * These variables are set for you according to the assignment definition. You can change them,
     * but it is not recommended. If you do change them please let us know and explain your reasoning for
     * changing them.
     */
    public final double gamma = 0.9;
    public final double learningRate = .0001;
    public final double epsilon = .02;

    /**
     * Construct a reinforcement learning agent.
     * @param playernum Player number of the agent
     * @param args String arguments
     */
    public RLAgent(int playernum, String[] args) {
        super(playernum);
        rewards = new HashMap<>();
        averageRewards = new LinkedList<>();
        averageRewards.add(0.0);

        if (args.length >= 1) {
            numEpisodes = Integer.parseInt(args[0]);
            System.out.println("Running " + numEpisodes + " episodes.");
        } else {
            numEpisodes = 10;
            System.out.println("Warning! Number of episodes not specified. Defaulting to 10 episodes.");
        }

        boolean loadWeights = false;
        if (args.length >= 2) {
            loadWeights = Boolean.parseBoolean(args[1]);
        } else {
            System.out.println("Warning! Load weights argument not specified. Defaulting to not loading.");
        }

        if (loadWeights) {
            weights = loadWeights();
        } else {
            // initialize weights to random values between -1 and 1
            weights = new Double[NUM_FEATURES];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = random.nextDouble() * 2 - 1;
            }
        }
    }

    /**
     * We've implemented some setup code for your convenience. Change what you need to.
     */
    @Override
    public Map<Integer, Action> initialStep(State.StateView stateView, History.HistoryView historyView) {

        myFootmen = new LinkedList<>();
        enemyFootmen = new LinkedList<>();

        decideToLearn();
        decideToTest();

        initializeFootmen(stateView, myFootmen, playernum);
        initializeFootmen(stateView, enemyFootmen, ENEMY_PLAYERNUM);

        for (Integer footman : myFootmen){
            rewards.put(footman, 0.0);
        }

        return middleStep(stateView, historyView);
    }

    /**
     * Initialize list of footmen for each player.
     * @param state Current state of the game
     * @param footmen List of footmen
     * @param player Player number of the agent
     */
    private void initializeFootmen(State.StateView state, List<Integer> footmen, int player) {
        for (Integer unitId : state.getUnitIds(player)) {
            Unit.UnitView unit = state.getUnit(unitId);
            String unitName = unit.getTemplateView().getName().toLowerCase();
            if (unitName.equals("footman"))
                footmen.add(unitId);
            else
                System.err.println("Unknown unit type: " + unitName);
        }
    }

    /**
     * You will need to calculate the reward at each step and update your totals. You will also need to
     * check if an event has occurred. If it has then you will need to update your weights and select a new action.
     *
     * If you are using the footmen vectors you will also need to remove killed units. To do so use the historyView
     * to get a DeathLog. Each DeathLog tells you which player's unit died and the unit ID of the dead unit. To get
     * the deaths from the last turn do something similar to the following snippet. Please be aware that on the first
     * turn you should not call this as you will get nothing back.
     *
     * for(DeathLog deathLog : historyView.getDeathLogs(stateView.getTurnNumber() -1)) {
     *     System.out.println("Player: " + deathLog.getController() + " unit: " + deathLog.getDeadUnitID());
     * }
     *
     * You should also check for completed actions using the history view. Obviously you never want a footman just
     * sitting around doing nothing (the enemy certainly isn't going to stop attacking). So at the minimum you will
     * have an even whenever one your footmen's targets is killed or an action fails. Actions may fail if the target
     * is surrounded or the unit cannot find a path to the unit. To get the action results from the previous turn
     * you can do something similar to the following. Please be aware that on the first turn you should not call this
     *
     * Map<Integer, ActionResult> actionResults = historyView.getCommandFeedback(playernum, stateView.getTurnNumber() - 1);
     * for(ActionResult result : actionResults.values()) {
     *     System.out.println(result.toString());
     * }
     *
     * @return New actions to execute or nothing if an event has not occurred.
     */
    @Override
    public Map<Integer, Action> middleStep(State.StateView stateView, History.HistoryView historyView) {

        Map<Integer, Action> actions = new HashMap<>();

        calculateRewards(stateView, historyView);
        if (checkForEvent(stateView, historyView)) {
            for (Integer friendlyFootmanId : myFootmen) {
                Integer enemyFootmanId = selectAction(stateView, historyView, friendlyFootmanId);
                if (!frozen) calcNewWeights(stateView, historyView, friendlyFootmanId, enemyFootmanId);
                actions.put(friendlyFootmanId, Action.createCompoundAttack(friendlyFootmanId, enemyFootmanId));
            }
        }

        if (stateView.getTurnNumber() > 0)
            removeDeadFootmen(stateView, historyView);

        return actions;
    }

    /**
     * Remove all dead footmen from their controlling agent's list of footmen.
     * @param stateView Current state of the game
     * @param historyView History of the game up to this point
     */
    private void removeDeadFootmen(State.StateView stateView, History.HistoryView historyView){
        for (DeathLog deathLog : historyView.getDeathLogs(stateView.getTurnNumber())) {
            Integer controller = deathLog.getController();
            Integer deadUnitID = deathLog.getDeadUnitID();
            if (controller == playernum && myFootmen.contains(deadUnitID))
                myFootmen.remove(deadUnitID);
            else if (controller == ENEMY_PLAYERNUM && enemyFootmen.contains(deadUnitID))
                enemyFootmen.remove(deadUnitID);
            else
                System.err.println("Unknown unit killed: " + stateView.getUnit(deadUnitID).getTemplateView().getName());
        }
    }

    /**
     * Calculate new weights to be applied.
     * @param stateView Current state of the game
     * @param historyView History of the game up to this turn
     * @param friendlyFootmanId Friendly footman ID
     * @param enemyFootmanId Enemy footman ID
     */
    private void calcNewWeights(State.StateView stateView, History.HistoryView historyView, int friendlyFootmanId, int enemyFootmanId) {
        updateWeights(weights, calculateFeatureVector(stateView, historyView, friendlyFootmanId, enemyFootmanId),
                rewards.get(friendlyFootmanId), stateView, historyView, friendlyFootmanId);
    }

    /**
     * Here you will calculate the cumulative average rewards for your testing episodes. If you have just
     * finished a set of test episodes you will call out testEdpisode.
     *
     * It is also a good idea to save your weights with the saveWeights function.
     */
    @Override
    public void terminalStep(State.StateView stateView, History.HistoryView historyView) {

        calculateRewards(stateView, historyView);
        removeDeadFootmen(stateView, historyView);

        if (episodeNumber > numEpisodes){
            System.out.println("ALL DONE");
            System.exit(0);
        }
        saveWeights(weights);

        if (myFootmen.size() > enemyFootmen.size()){
            System.out.println("VICTORY!");
        } else {
            System.out.println("DEFEAT");
        }
    }

    /**
     * Configure this agent for learning.
     */
    private void decideToLearn() {

        if (frozen) return;

        if (learningEpisodes < 10) {
            episodeNumber++;
            System.out.println(episodeNumber);
            learningEpisodes++;
        } else {
            frozen = true;
            learningEpisodes = 0;
        }
    }

    /**
     * Configure this agent for testing.
     */
    private void decideToTest() {

        if (!frozen)return;

        if (testingEpisodes < 5) {
            testingEpisodes++;
            double totalReward = 0;
            for (Double reward : rewards.values())
                totalReward += reward;
            averageReward += (totalReward / rewards.size());
        } else {
            frozen = false;
            testingEpisodes = 0;
            averageRewards.add(averageReward / 5);
            printTestData(averageRewards);
            saveToCsv(averageRewards);
            averageReward = 0;
        }
    }

    /**
     * Calculate the updated weights for this agent.
     * @param oldWeights Weights prior to update
     * @param oldFeatures Features from (s,a)
     * @param totalReward Cumulative discounted reward for this footman.
     * @param stateView Current state of the game.
     * @param historyView History of the game up until this point
     * @param footmanId The footman we are updating the weights for
     * @return The updated weight vector.
     */
    public double[] updateWeights(Double[] oldWeights, double[] oldFeatures,
                                  double totalReward, State.StateView stateView,
                                  History.HistoryView historyView, int footmanId) {

        double[] newWeights = new double[oldWeights.length];

        for (int i = 0; i < newWeights.length; i++) {
            double q = 0;
            for (int j = 0; j < oldFeatures.length; j++)
                q += oldWeights[j] * oldFeatures[j];
            if (!frozen) {
                for (Integer id : enemyFootmen) {
                    double totalQ = calcQValue(stateView, historyView, footmanId, id);
                    if (totalQ > this.totalQ)
                        this.totalQ = totalQ;
                }
            }
            newWeights[i] = getNextWeight(totalReward, q, oldFeatures[i]);
        }
        return newWeights;
    }

    /**
     * Get the next weight to apply.
     * @param totalReward Total reward of the current state of the game
     * @param q The Q value
     * @param feature The feature in question
     * @return The next weight
     */
    private double getNextWeight(double totalReward, double q, double feature) {
        double targetQ = totalReward + gamma * totalQ;
        double loss = (q - targetQ) * feature;
        return  feature - learningRate * loss;
    }

    /**
     * Given a footman and the current state and history of the game select the enemy that this unit should
     * attack. This is where you would do the epsilon-greedy action selection.
     *
     * @param stateView Current state of the game
     * @param historyView The entire history of this episode
     * @param attackerId The footman that will be attacking
     * @return The enemy footman ID this unit should attack
     */
    public int selectAction(State.StateView stateView, History.HistoryView historyView, int attackerId) {

        if (enemyFootmen.isEmpty())  // target nobody if the enemy agent is defeated
            return -1;

        // target random enemy on first turn... or with a probability of epsilon
        if (stateView.getTurnNumber() == 0  || random.nextDouble() < epsilon)
            return enemyFootmen.get((int) (random.nextDouble() * enemyFootmen.size()));

        Integer defenderId = enemyFootmen.get(0);
        for (Integer tempDefenderId : enemyFootmen) {
            double tempQ = calcQValue(stateView, historyView, attackerId, tempDefenderId);
            if (tempQ > totalQ) {  // find which target maximizes the Q value
                totalQ = tempQ;
                defenderId = tempDefenderId;
            }
        }
        return defenderId;
    }

    /**
     * Given the current state and the footman in question calculate the reward received on the last turn.
     * This is where you will check for things like Did this footman take or give damage? Did this footman die
     * or kill its enemy. Did this footman start an action on the last turn? See the assignment description
     * for the full list of rewards.
     *
     * Remember that you will need to discount this reward based on the timestep it is received on. See
     * the assignment description for more details.
     *
     * As part of the reward you will need to calculate if any of the units have taken damage. You can use
     * the history view to get a list of damages dealt in the previous turn. Use something like the following.
     *
     * for(DamageLog damageLogs : historyView.getDamageLogs(lastTurnNumber)) {
     *     System.out.println("Defending player: " + damageLog.getDefenderController() + " defending unit: " + \
     *     damageLog.getDefenderID() + " attacking player: " + damageLog.getAttackerController() + \
     *     "attacking unit: " + damageLog.getAttackerID());
     * }
     *
     * You will do something similar for the deaths. See the middle step documentation for a snippet
     * showing how to use the deathLogs.
     *
     * To see if a command was issued you can check the commands issued log.
     *
     * Map<Integer, Action> commandsIssued = historyView.getCommandsIssued(playernum, lastTurnNumber);
     * for (Map.Entry<Integer, Action> commandEntry : commandsIssued.entrySet()) {
     *     System.out.println("Unit " + commandEntry.getKey() + " was command to " + commandEntry.getValue().toString);
     * }
     *
     * @param stateView The current state of the game.
     * @param historyView History of the episode up until this turn.
     * @param footmanId The footman ID you are looking for the reward from.
     * @return The current reward
     */
    public double calculateReward(State.StateView stateView, History.HistoryView historyView, int footmanId) {

        double reward = 0;
        int previousTurnNumber = stateView.getTurnNumber() - 1;

        if (previousTurnNumber < 0)  // game just started
            return reward;

        for (DamageLog damageLog : historyView.getDamageLogs(previousTurnNumber)) {
            if (damageLog.getAttackerID() == footmanId)  // footman did damage
                reward += damageLog.getDamage();
            else if (damageLog.getDefenderID() == footmanId)  // footman took damage
                reward -= damageLog.getDamage();
        }

        for (DeathLog deathLog : historyView.getDeathLogs(previousTurnNumber)) {
            Integer deadFootmanId = deathLog.getDeadUnitID();
            if (myFootmen.contains(deadFootmanId))  // friendly footman died
                reward -= 100;
            else if (enemyFootmen.contains(deadFootmanId))  // enemy footman died
                reward += 100;
        }

        if (historyView.getCommandsIssued(playernum, previousTurnNumber).containsKey(footmanId))
            reward -= 0.1;  // account for cost of performing an action

        return reward;
    }

    /**
     * Calculate all rewards received on the last turn.
     * @param stateView Current state of the game
     * @param historyView History of the game up to this turn
     */
    private void calculateRewards(State.StateView stateView, History.HistoryView historyView){
        for (Integer footmanId : myFootmen) {
            double stateReward = calculateReward(stateView, historyView, footmanId);
            double currentReward = rewards.get(footmanId);
            rewards.put(footmanId, stateReward + currentReward);
        }
    }

    /**
     * Calculate the Q-Value for a given state action pair. The state in this scenario is the current
     * state view and the history of this episode. The action is the attacker and the enemy pair for the
     * SEPIA attack action.
     *
     * This returns the Q-value according to your feature approximation. This is where you will calculate
     * your features and multiply them by your current weights to get the approximate Q-value.
     *
     * @param stateView Current SEPIA state
     * @param historyView Episode history up to this point in the game
     * @param attackerId Your footman. The one doing the attacking.
     * @param defenderId An enemy footman that your footman would be attacking
     * @return The approximate Q-value
     */
    public double calcQValue(State.StateView stateView, History.HistoryView historyView, int attackerId, int defenderId) {
        double[] features = calculateFeatureVector(stateView, historyView, attackerId, defenderId);
        double q = 0;
        for (int i = 0; i < features.length; i++)
            q += features[i] * weights[i];
        return q;
    }

    /**
     * Given a state and action calculate your features here. Please include a comment explaining what features
     * you chose and why you chose them.
     *
     * All of your feature functions should evaluate to a double. Collect all of these into an array. You will
     * take a dot product of this array with the weights array to get a Q-value for a given state action.
     *
     * It is a good idea to make the first value in your array a constant. This just helps remove any offset
     * from 0.0 in the Q-function. The other features are up to you. Many are suggested in the assignment
     * description.
     *
     * @param stateView Current state of the SEPIA game
     * @param historyView History of the game up until this turn
     * @param attackerId Your footman. The one doing the attacking.
     * @param defenderId An enemy footman. The one you are considering attacking.
     * @return The array of feature function outputs.
     */
    public double[] calculateFeatureVector(State.StateView stateView, History.HistoryView historyView, int attackerId, int defenderId) {

        double[] featureVector = new double[NUM_FEATURES];       // instantiate feature vector
        Unit.UnitView attacker = stateView.getUnit(attackerId);  // get attacker unit view
        Unit.UnitView defender = stateView.getUnit(defenderId);  // get defender unit view

        // Feature 1: constant
        featureVector[0] = 1;

        if (attacker == null || defender == null)
            return featureVector;

        // Feature 2: adjacency to enemy footman
        featureVector[1] = 100 * (1 / chebyshevDistance(
                attacker.getXPosition(), attacker.getYPosition(),
                defender.getXPosition(), defender.getYPosition()));

        // Feature 3: ratio of hitpoints
        featureVector[2] = defender.getHP() > 0 ? (double) attacker.getHP() / defender.getHP() : 1;

        Map<Integer, ActionResult> actionResults =
                historyView.getCommandFeedback(playernum, stateView.getTurnNumber() - 1);

        // Feature 4: successfully attacked enemy
       if (actionResults != null && actionResults.containsKey(attackerId)) {
               TargetedAction attack = (TargetedAction) actionResults.get(attackerId).getAction();
               if (attack != null && attack.getTargetId() == defenderId)
                   featureVector[3] = 100;
               else
                   featureVector[3] = 1;
       }

        // Feature 5: assisting footmen
        int numAttackers = 0;
        if (actionResults != null) {
            for (ActionResult ar : actionResults.values())
                if (((TargetedAction) ar.getAction()).getTargetId() == defenderId)
                    numAttackers++;
        }
        featureVector[4] = numAttackers > 0 ? 1.0 / numAttackers : 1;

        return featureVector;
    }

    /**
     * Check whether a significant event happened during the previous turn.
     * @param stateView Current state of the game
     * @param historyView History of the game up until this turn
     * @return <code>true</code> if a significant event happened; <code>false</code> otherwise
     */
    private boolean checkForEvent(State.StateView stateView, History.HistoryView historyView) {

        int previousTurnNumber = stateView.getTurnNumber() - 1;

        if (previousTurnNumber < 0)  // game just started
            return true;

        if (historyView.getDeathLogs(previousTurnNumber).size() > 0)  // somebody died
            return true;

        for (DamageLog damageLog : historyView.getDamageLogs(previousTurnNumber))
            if (myFootmen.contains(damageLog.getDefenderID()))  // somebody done got hurt
                return true;

        Map<Integer, ActionResult> actionResults = historyView.getCommandFeedback(playernum, previousTurnNumber);
        for (ActionResult ar : actionResults.values())
            if (myFootmen.contains(ar.getAction().getUnitId()) && ar.getFeedback().toString().equals("INCOMPLETE"))
                return true;  // somebody ain't done with their action

        return false;
    }

    /**
     * DO NOT CHANGE THIS!
     *
     * Prints the learning rate data described in the assignment. Do not modify this method.
     *
     * @param averageRewards List of cumulative average rewards from test episodes.
     */
    public void printTestData (List<Double> averageRewards) {
        System.out.println("");
        System.out.println("Games Played      Average Cumulative Reward");
        System.out.println("-------------     -------------------------");
        for (int i = 0; i < averageRewards.size(); i++) {
            String gamesPlayed = Integer.toString(10*i);
            String averageReward = String.format("%.2f", averageRewards.get(i));

            int numSpaces = "-------------     ".length() - gamesPlayed.length();
            StringBuffer spaceBuffer = new StringBuffer(numSpaces);
            for (int j = 0; j < numSpaces; j++) {
                spaceBuffer.append(" ");
            }
            System.out.println(gamesPlayed + spaceBuffer.toString() + averageReward);
        }
        System.out.println("");
    }

    /**
     * Save average cumulative rewards acquired during this run to a CSV file.
     * @param averageRewards Average cumulative rewards
     */
    private void saveToCsv(List<Double> averageRewards) {
        File path = new File("outputs/rewards.csv");
        path.getAbsoluteFile().getParentFile().mkdirs();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
            for (int i = 0; i < averageRewards.size(); i++) {
                String gamesPlayed = Integer.toString(10 * i);
                String averageReward = String.format("%.2f", averageRewards.get(i));
                bw.write(String.format("%s,%s\n", gamesPlayed, averageReward));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * DO NOT CHANGE THIS!
     *
     * This function will take your set of weights and save them to a file. Overwriting whatever file is
     * currently there. You will use this when training your agents. You will include th output of this function
     * from your trained agent with your submission.
     *
     * Look in the agent_weights folder for the output.
     *
     * @param weights Array of weights
     */
    public void saveWeights(Double[] weights) {
        File path = new File("agent_weights/weights.txt");
        // create the directories if they do not already exist
        path.getAbsoluteFile().getParentFile().mkdirs();

        try {
            // open a new file writer. Set append to false
            BufferedWriter writer = new BufferedWriter(new FileWriter(path, false));

            for (double weight : weights) {
                writer.write(String.format("%f\n", weight));
            }
            writer.flush();
            writer.close();
        } catch(IOException ex) {
            System.err.println("Failed to write weights to file. Reason: " + ex.getMessage());
        }
    }

    /**
     * DO NOT CHANGE THIS!
     *
     * This function will load the weights stored at agent_weights/weights.txt. The contents of this file
     * can be created using the saveWeights function. You will use this function if the load weights argument
     * of the agent is set to 1.
     *
     * @return The array of weights
     */
    public Double[] loadWeights() {
        File path = new File("agent_weights/weights.txt");
        if (!path.exists()) {
            System.err.println("Failed to load weights. File does not exist");
            return null;
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(path));
            String line;
            List<Double> weights = new LinkedList<>();
            while((line = reader.readLine()) != null) {
                weights.add(Double.parseDouble(line));
            }
            reader.close();

            return weights.toArray(new Double[weights.size()]);
        } catch(IOException ex) {
            System.err.println("Failed to load weights from file. Reason: " + ex.getMessage());
        }
        return null;
    }

    @Override
    public void savePlayerData(OutputStream outputStream) {

    }

    @Override
    public void loadPlayerData(InputStream inputStream) {

    }
}
