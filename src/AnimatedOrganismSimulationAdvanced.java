// AnimatedOrganismSimulationAdvanced.java
// JavaFX single-file simulation: cells -> organisms -> differentiation -> substrate -> food patches -> predation
// 1 minute (60000 ms) = 1 simulated day by default.

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Advanced single-file JavaFX simulation implementing:
 * - Time scale: 1 minute = 1 day (SIM_MS_PER_DAY = 60000)
 * - Cell differentiation into types (STEM, NERVE, MUSCLE, EPITHELIAL, IMMUNE)
 * - Spatial substrate (tissue matrix grid) that influences adhesion and movement
 * - Food patches that replenish cells' energy
 * - Predation / inter-organism attacks via AGITATED cells
 * - Each cell has its own mood driving behaviour; organisms have hormones
 *
 * Save as AnimatedOrganismSimulationAdvanced.java and run with JavaFX on module path.
 */
public class AnimatedOrganismSimulationAdvanced extends Application {

    // ---------- Simulation parameters ----------
    private static final double WIDTH = 1200;
    private static final double HEIGHT = 720;

    private static final double CELL_RADIUS = 6;
    private static final double CONNECT_DIST = 28;
    private static final double MAX_VELOCITY = 30;

    private static final double MAX_ENERGY = 120.0;
    private static final double ENERGY_DECAY_PER_DAY = 1.2;
    private static final double ENERGY_FROM_FEED = 18.0;

    private static final double DIVIDE_ENERGY_THRESHOLD = 70.0;
    private static final double DIVIDE_COST = 35.0;

    private static final int MAX_AGE_DAYS = 250;

    // Time scale: 1 minute = 1 day (60000 ms)
    private static final long SIM_MS_PER_DAY = 60000L;

    private static final double DIVIDE_PROB_PER_DAY = 0.12;
    private static final double CONNECT_CHANCE = 0.14;

    private static final int INITIAL_CELLS = 1;
    private static final int FOOD_PATCHES = 10;

    // Tissue substrate grid
    private static final int GRID_W = 60; // coarse grid
    private static final int GRID_H = 36;

    // ---------- State ----------
    private final List<Cell> cells = new CopyOnWriteArrayList<>();
    private final Map<Integer, Organism> organisms = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<FoodPatch> foods = Collections.synchronizedList(new ArrayList<>());
    private final Random rng = new Random();

    private final AtomicInteger cellIdGen = new AtomicInteger(1);
    private final AtomicInteger organismIdGen = new AtomicInteger(1);

    // substrate
    private final double[][] substrateAdhesion = new double[GRID_W][GRID_H];

    // UI
    private Canvas canvas;
    private GraphicsContext gc;
    private VBox rightPanel;
    private TextArea logArea;
    private Label timeLabel;

    private long lastSimTimeMs;
    private long accTimeMs = 0;
    private long simDaysElapsed = 0;

    private boolean running = true;

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        canvas = new Canvas(WIDTH, HEIGHT);
        gc = canvas.getGraphicsContext2D();

        rightPanel = new VBox(8);
        rightPanel.setPadding(new Insets(8));
        rightPanel.setPrefWidth(360);

        Label title = new Label("Advanced Organism Simulator");
        title.setFont(new Font(18));
        rightPanel.getChildren().add(title);

        timeLabel = new Label("Day: 0");
        rightPanel.getChildren().add(timeLabel);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(10);

        HBox controls = new HBox(6);
        Button pauseBtn = new Button("Pause");
        pauseBtn.setOnAction(e -> { running = !running; pauseBtn.setText(running ? "Pause" : "Resume"); });
        Button resetBtn = new Button("Reset");
        resetBtn.setOnAction(e -> Platform.runLater(this::resetSimulation));
        controls.getChildren().addAll(pauseBtn, resetBtn);

        rightPanel.getChildren().addAll(controls, new Label("Event log:"), logArea);

        root.setCenter(new StackPane(canvas));
        root.setRight(rightPanel);

        Scene scene = new Scene(root);
        primaryStage.setTitle("Animated Organism Simulation Advanced");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
        primaryStage.show();

        initializeSubstrate();
        initializeFoods();
        initializeCells();

        lastSimTimeMs = System.currentTimeMillis();

        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!running) { render(); return; }
                long cur = System.currentTimeMillis();
                long dt = cur - lastSimTimeMs;
                lastSimTimeMs = cur;
                accTimeMs += dt;
                while (accTimeMs >= SIM_MS_PER_DAY) {
                    accTimeMs -= SIM_MS_PER_DAY;
                    dayTick();
                    simDaysElapsed++;
                }
                double seconds = dt / 1000.0;
                continuousUpdate(seconds);
                render();
            }
        };
        timer.start();
    }

    private void initializeSubstrate() {
        // create random adhesion field (0..1). Some areas encourage connections / tissue building
        for (int i = 0; i < GRID_W; i++) {
            for (int j = 0; j < GRID_H; j++) {
                double base = 0.2 + rng.nextDouble()*0.6;
                // introduce smooth patches
                substrateAdhesion[i][j] = Math.min(1.0, Math.max(0.0, base + (Math.sin(i*0.3)+Math.cos(j*0.2))*0.05));
            }
        }
    }

    private void initializeFoods() {
        foods.clear();
        for (int i = 0; i < FOOD_PATCHES; i++) {
            foods.add(new FoodPatch(rng.nextDouble()*WIDTH, rng.nextDouble()*HEIGHT, 80 + rng.nextDouble()*160));
        }
    }

    private void initializeCells() {
        cells.clear();
        organisms.clear();
        cellIdGen.set(1);
        organismIdGen.set(1);
        // start with a single stem cell in center
        spawnCell(WIDTH/2, HEIGHT/2, CellType.STEM, null);
    }

    private void resetSimulation() {
        simDaysElapsed = 0;
        accTimeMs = 0;
        initializeSubstrate();
        initializeFoods();
        initializeCells();
        log("Simulation reset.");
    }

    // ---------- Day tick ----------
    private void dayTick() {
        // Each cell ages, consumes energy, may change mood, may divide or die
        for (Cell c : new ArrayList<>(cells)) c.dayActions();

        // environment: food patches slowly regenerate
        for (FoodPatch f : foods) f.regenerate(6);

        // connections
        for (int i = 0; i < cells.size(); i++) {
            Cell a = cells.get(i);
            for (int j = i+1; j < cells.size(); j++) {
                Cell b = cells.get(j);
                if (!a.alive || !b.alive) continue;
                double d = a.distanceTo(b);
                if (d <= CONNECT_DIST) {
                    if (!a.isConnectedTo(b) && rng.nextDouble() < CONNECT_CHANCE * substrateAdhesionAt((a.x+b)/2,(a.y+b)/2)) {
                        connectCells(a,b);
                    }
                }
            }
        }

        // organisms update
        for (Organism o : new ArrayList<>(organisms.values())) o.dailyUpdate();

        // predation: organism-level aggression triggers coordinate attacks
        for (Organism o : new ArrayList<>(organisms.values())) o.handlePredation();

        // cleanup dead cells and organisms
        cells.removeIf(c -> !c.alive);
        List<Integer> deadOrgs = new ArrayList<>();
        for (Organism o : organisms.values()) if (o.isDead()) deadOrgs.add(o.id);
        for (int id : deadOrgs) {
            Organism removed = organisms.remove(id);
            if (removed != null) log("Organism#"+id+" perished.");
        }
    }

    private double substrateAdhesionAt(double x, double y) {
        int gx = (int) (Math.max(0, Math.min(GRID_W-1, (x / WIDTH) * GRID_W)));
        int gy = (int) (Math.max(0, Math.min(GRID_H-1, (y / HEIGHT) * GRID_H)));
        return substrateAdhesion[gx][gy];
    }

    // ---------- Continuous update for movement ----------
    private void continuousUpdate(double seconds) {
        for (Cell c : cells) c.continuousUpdate(seconds);
        // small drift for food patches (optional static)
    }

    // ---------- Spawning & connections ----------
    private void spawnCell(double x, double y, CellType type, Organism parent) {
        int id = cellIdGen.getAndIncrement();
        Cell c = new Cell(id, x, y, type);
        cells.add(c);
        if (parent == null) {
            Organism o = new Organism(organismIdGen.getAndIncrement(), c);
            organisms.put(o.id, o);
            log("Organism#"+o.id+" formed (seed cell#"+c.id+").");
        } else {
            parent.addCell(c);
            c.organismId = parent.id;
        }
    }

    private void connectCells(Cell a, Cell b) {
        a.connectTo(b); b.connectTo(a);
        // merge organisms or create new
        if (a.organismId == -1 && b.organismId == -1) {
            Organism o = new Organism(organismIdGen.getAndIncrement(), a, b);
            organisms.put(o.id, o);
            log("Organism#"+o.id+" formed by connection ("+a.id+","+b.id+").");
        } else if (a.organismId != -1 && b.organismId == -1) {
            Organism o = organisms.get(a.organismId); if (o!=null) { o.addCell(b); b.organismId = o.id; }
        } else if (b.organismId != -1 && a.organismId == -1) {
            Organism o = organisms.get(b.organismId); if (o!=null) { o.addCell(a); a.organismId = o.id; }
        } else if (a.organismId != -1 && b.organismId != -1 && a.organismId != b.organismId) {
            Organism oa = organisms.get(a.organismId); Organism ob = organisms.get(b.organismId);
            if (oa != null && ob != null) {
                Organism larger = oa.size() >= ob.size() ? oa : ob;
                Organism smaller = larger==oa?ob:oa;
                for (Cell cell: new ArrayList<>(smaller.members())) { larger.addCell(cell); cell.organismId = larger.id; }
                organisms.remove(smaller.id);
                log("Organism#"+larger.id+" absorbed Organism#"+smaller.id+");
            }
        }
    }

    // ---------- Rendering ----------
    private void render() {
        gc.setFill(Color.rgb(12,12,18)); gc.fillRect(0,0,WIDTH,HEIGHT);

        // draw substrate adhesion as faint background
        for (int i=0;i<GRID_W;i++){
            for (int j=0;j<GRID_H;j++){
                double v = substrateAdhesion[i][j];
                gc.setFill(Color.gray(0.1 + 0.4*v, 0.08));
                double x = (i/(double)GRID_W)*WIDTH; double y = (j/(double)GRID_H)*HEIGHT;
                gc.fillRect(x,y, WIDTH/GRID_W+1, HEIGHT/GRID_H+1);
            }
        }

        // draw food patches
        for (FoodPatch f: foods) {
            double radius = Math.min(40, 6 + f.amount/6.0);
            gc.setFill(Color.rgb(255,200,120,0.55));
            gc.fillOval(f.x-radius, f.y-radius, radius*2, radius*2);
            gc.setStroke(Color.rgb(220,140,80,0.9));
            gc.strokeOval(f.x-radius, f.y-radius, radius*2, radius*2);
        }

        // draw connections
        gc.setLineWidth(1.0);
        for (Cell c : cells) {
            for (Integer otherId : c.connections) {
                Cell o = findById(otherId);
                if (o != null) {
                    gc.setStroke(Color.gray(0.7,0.25));
                    gc.strokeLine(c.x, c.y, o.x, o.y);
                }
            }
        }

        // draw cells
        for (Cell c : cells) {
            Color fill = cellTypeColor(c.type);
            // mood tint
            if (c.mood == Mood.HUNGRY) fill = blend(fill, Color.ORANGE, 0.35);
            else if (c.mood == Mood.DIVIDE) fill = blend(fill, Color.PURPLE, 0.3);
            else if (c.mood == Mood.AGITATED) fill = blend(fill, Color.RED, 0.45);
            if (!c.alive) fill = Color.GRAY;
            gc.setFill(fill);
            gc.fillOval(c.x-CELL_RADIUS, c.y-CELL_RADIUS, CELL_RADIUS*2, CELL_RADIUS*2);
            gc.setStroke(Color.BLACK);
            gc.strokeOval(c.x-CELL_RADIUS, c.y-CELL_RADIUS, CELL_RADIUS*2, CELL_RADIUS*2);
        }

        gc.setFill(Color.WHITE);
        gc.fillText("Day: " + simDaysElapsed + "  Cells: " + cells.size() + "  Organisms: " + organisms.size(), 10, 14);

        Platform.runLater(this::updateRightPanel);
    }

    private Color blend(Color a, Color b, double t) {
        return new Color(a.getRed()*(1-t)+b.getRed()*t, a.getGreen()*(1-t)+b.getGreen()*t, a.getBlue()*(1-t)+b.getBlue()*t, 1.0);
    }

    private void updateRightPanel() {
        rightPanel.getChildren().removeIf(node -> node instanceof VBox || node instanceof Separator);
        VBox list = new VBox(6); list.setPadding(new Insets(6));
        for (Organism o : new ArrayList<>(organisms.values())) {
            Label h = new Label("Organism#"+o.id + (o.isDead()?" (dead)":"")); h.setTextFill(Color.WHITE);
            Label stats = new Label("Cells: "+o.size()+"  Hormone: "+String.format("%.2f",o.hormone)+"  Age: "+o.ageDays);
            stats.setTextFill(Color.LIGHTGRAY);
            list.getChildren().addAll(h, stats, new Separator());
        }
        rightPanel.getChildren().add(2, new Separator());
        rightPanel.getChildren().add(3, list);
        timeLabel.setText("Day: " + simDaysElapsed + "  Food patches: " + foods.size());
    }

    // ---------- Utilities ----------
    private Cell findById(int id) { for (Cell c: cells) if (c.id==id) return c; return null; }
    private String randomGenome(int len) { String letters="ABCDEFGHIJKLMNOPQRSTUVWXYZ"; StringBuilder sb=new StringBuilder(); for(int i=0;i<len;i++) sb.append(letters.charAt(rng.nextInt(letters.length()))); return sb.toString(); }
    private Color cellTypeColor(CellType t) {
        return switch(t) {
            case STEM ->