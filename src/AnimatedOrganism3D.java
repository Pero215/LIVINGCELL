// AnimatedOrganism3D.java
// JavaFX 3D low-poly organism simulation (single-file)
// Save and run with JavaFX (module path --add-modules javafx.controls,javafx.graphics)

import javafx.animation.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.*;
import javafx.scene.shape.*;
import javafx.scene.text.Font;
import javafx.scene.transform.Rotate;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AnimatedOrganism3D
 *
 * Features:
 * - Low-poly 3D cells (icosahedron-based TriangleMesh)
 * - Organisms = groups of cells; visualized as Groups in 3D scene graph
 * - organismsTouching(...) implemented by bounding sphere overlap
 * - Smooth merging animation + color/scale blending
 * - Predatory moods trigger coordinated attacks and absorptions
 * - 1 minute = 1 day (SIM_MS_PER_DAY = 60000L)
 *
 * Usage:
 * Compile and run with JavaFX on module path.
 */
public class AnimatedOrganism3D extends Application {

    // Scene size
    private static final int SCENE_W = 1200;
    private static final int SCENE_H = 800;

    // Simulation controls
    private static final long SIM_MS_PER_DAY = 60000L; // 1 minute = 1 day
    private static final int INITIAL_CELLS = 1;
    private static final double CELL_BASE_RADIUS = 8.0; // visual radius in 3D
    private static final double WORLD_SCALE = 1.0; // used to scale motion

    // Energy & behavior
    private static final double MAX_ENERGY = 120.0;
    private static final double ENERGY_DECAY_PER_DAY = 1.2;
    private static final double ENERGY_FROM_FOOD = 20.0;
    private static final double DIVIDE_THRESHOLD = 70.0;
    private static final double DIVIDE_COST = 35.0;

    // Distances
    private static final double CONNECT_DIST = 48.0;
    private static final double ORGANISM_TOUCH_DIST = 60.0; // bounding-sphere threshold for organismsTouching

    // Division & reproduction probabilities
    private static final double DIVIDE_PROB = 0.12;

    // IDs
    private final AtomicInteger cellIdGen = new AtomicInteger(1);
    private final AtomicInteger orgIdGen = new AtomicInteger(1);

    // Collections
    private final List<Cell> cells = new CopyOnWriteArrayList<>();
    private final Map<Integer, Organism> organisms = Collections.synchronizedMap(new LinkedHashMap<>());

    // JavaFX scene graph
    private Group root3D = new Group();
    private SubScene subScene3D;
    private PerspectiveCamera camera;
    private Rotate cameraRotateX = new Rotate(-30, Rotate.X_AXIS);
    private Rotate cameraRotateY = new Rotate(-30, Rotate.Y_AXIS);
    private double cameraDistance = -900;

    // UI
    private TextArea logArea;
    private VBox rightPanel;
    private Label dayLabel;
    private long lastTimeMs;
    private long accMs = 0;
    private long simDays = 0;
    private boolean running = true;

    private final Random rng = new Random();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        buildUI(primaryStage);
        initSimulation();

        lastTimeMs = System.currentTimeMillis();
        AnimationTimer loop = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!running) {
                    return;
                }
                long cur = System.currentTimeMillis();
                long dt = cur - lastTimeMs;
                lastTimeMs = cur;
                accMs += dt;

                // Advance day ticks according to time scale
                while (accMs >= SIM_MS_PER_DAY) {
                    accMs -= SIM_MS_PER_DAY;
                    tickDay();
                    simDays++;
                    dayLabel.setText("Day: " + simDays);
                }

                // continuous updates for smooth animation
                double seconds = dt / 1000.0;
                continuousUpdate(seconds);

                // lightweight check for organism collisions to trigger merges
                checkOrganismCollisionsAndHandle();

            }
        };
        loop.start();
    }

    private void buildUI(Stage primaryStage) {
        BorderPane root = new BorderPane();

        // 3D subscene
        root3D = new Group();
        subScene3D = new SubScene(root3D, SCENE_W, SCENE_H, true, SceneAntialiasing.BALANCED);
        subScene3D.setFill(Color.rgb(10, 10, 15));
        camera = new PerspectiveCamera(true);
        camera.setNearClip(0.1);
        camera.setFarClip(5000);
        camera.getTransforms().addAll(cameraRotateX, cameraRotateY, new Rotate(0, Rotate.Z_AXIS));
        camera.setTranslateZ(cameraDistance);
        subScene3D.setCamera(camera);

        // simple ambient + point light
        AmbientLight ambient = new AmbientLight(Color.rgb(80, 80, 90));
        PointLight pl = new PointLight(Color.rgb(220, 220, 210));
        pl.setTranslateX(-200);
        pl.setTranslateY(-300);
        pl.setTranslateZ(-200);
        root3D.getChildren().addAll(ambient, pl);

        // UI right panel
        rightPanel = new VBox(6);
        rightPanel.setPadding(new Insets(8));
        rightPanel.setPrefWidth(360);

        Label title = new Label("Organism 3D Monitor");
        title.setFont(new Font(18));
        dayLabel = new Label("Day: 0");
        Button pauseBtn = new Button("Pause");
        pauseBtn.setOnAction(e -> {
            running = !running;
            pauseBtn.setText(running ? "Pause" : "Resume");
        });
        Button addFoodBtn = new Button("Spawn Organism");
        addFoodBtn.setOnAction(e -> spawnRandomOrganism());
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(12);

        rightPanel.getChildren().addAll(title, dayLabel, new HBox(6, pauseBtn, addFoodBtn), new Label("Events:"), logArea);

        root.setCenter(subScene3D);
        root.setRight(rightPanel);

        Scene scene = new Scene(root);
        primaryStage.setTitle("Animated Organism 3D - Low Poly");
        primaryStage.setScene(scene);
        primaryStage.show();

        // Basic mouse control for camera rotation
        final Delta dragDelta = new Delta();
        subScene3D.setOnMousePressed(ev -> {
            dragDelta.x = ev.getSceneX();
            dragDelta.y = ev.getSceneY();
        });
        subScene3D.setOnMouseDragged(ev -> {
            double dx = ev.getSceneX() - dragDelta.x;
            double dy = ev.getSceneY() - dragDelta.y;
            dragDelta.x = ev.getSceneX();
            dragDelta.y = ev.getSceneY();
            cameraRotateY.setAngle(cameraRotateY.getAngle() + dx * 0.2);
            cameraRotateX.setAngle(cameraRotateX.getAngle() - dy * 0.2);
        });

        // Zoom with scroll
        subScene3D.setOnScroll(ev -> {
            cameraDistance += ev.getDeltaY() * 0.8;
            camera.setTranslateZ(cameraDistance);
        });
    }

    private void initSimulation() {
        // clear scene
        root3D.getChildren().removeIf(n -> !(n instanceof LightBase));
        cells.clear();
        organisms.clear();

        // spawn a seed organism in center
        spawnOrganismAt(0, 0, 0, 6); // initial 6 cells
    }

    // spawn an organism at given 3D coords with n initial cells
    private void spawnOrganismAt(double cx, double cy, double cz, int n) {
        // create one organism object first
        Organism o = new Organism(orgIdGen.getAndIncrement());
        organisms.put(o.id, o);

        // group in scene for visual nodes
        Group orgGroup = o.group;
        orgGroup.setTranslateX(cx);
        orgGroup.setTranslateY(cy);
        orgGroup.setTranslateZ(cz);
        root3D.getChildren().add(orgGroup);

        for (int i = 0; i < n; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double r = 30 + rng.nextDouble() * 36;
            double x = cx + Math.cos(angle) * r;
            double y = cy + (rng.nextDouble() - 0.5) * 40;
            double z = cz + Math.sin(angle) * r;
            spawnCell(x, y, z, o);
        }
        log("Organism#" + o.id + " spawned with " + n + " cells");
    }

    private void spawnRandomOrganism() {
        double cx = (rng.nextDouble() - 0.5) * 400;
        double cy = (rng.nextDouble() - 0.5) * 200;
        double cz = (rng.nextDouble() - 0.5) * 400;
        spawnOrganismAt(cx, cy, cz, 4 + rng.nextInt(6));
    }

    private void spawnCell(double x, double y, double z, Organism parent) {
        int id = cellIdGen.getAndIncrement();
        Cell c = new Cell(id, x, y, z);
        cells.add(c);
        // add to parent organism (visual & logical)
        parent.addCell(c);
        c.organism = parent;
    }

    // --------------- Day tick (discrete) ----------------
    private void tickDay() {
        // cells: age, metabolism, decide moods and perform daily actions
        for (Cell c : new ArrayList<>(cells)) {
            c.dayTick();
        }

        // organisms update: hormones, aggression decisions
        for (Organism o : new ArrayList<>(organisms.values())) {
            o.dailyUpdate();
        }

        // handle divisions (children are added inside cell.divide)
        // remove dead cells
        cells.removeIf(c -> !c.alive);

        // remove dead organisms (no living members)
        List<Integer> dead = new ArrayList<>();
        for (Organism o : organisms.values()) if (o.isDead()) dead.add(o.id);
        for (int id : dead) {
            Organism rem = organisms.remove(id);
            if (rem != null) {
                // animate fade-out
                FadeTransition ft = new FadeTransition(Duration.seconds(1.2), rem.group);
                ft.setFromValue(1.0);
                ft.setToValue(0.0);
                ft.setOnFinished(e -> root3D.getChildren().remove(rem.group));
                ft.play();
                log("Organism#" + id + " died out");
            }
        }
    }

    // continuous smooth movement
    private void continuousUpdate(double seconds) {
        for (Cell c : cells) c.continuousUpdate(seconds);
    }

    // ---------- organism collision detection & handling ----------
    private void checkOrganismCollisionsAndHandle() {
        // naive O(n^2) over organisms; acceptable for modest numbers. Use spatial partitioning for scaling.
        List<Organism> list = new ArrayList<>(organisms.values());
        int n = list.size();
        for (int i = 0; i < n; i++) {
            Organism a = list.get(i);
            for (int j = i + 1; j < n; j++) {
                Organism b = list.get(j);
                if (a == null || b == null) continue;
                if (a.isDead() || b.isDead()) continue;

                if (organismsTouching(a, b)) {
                    // merging logic: if one organism is aggressive (predatory) it will absorb the other
                    if (a.shouldAbsorb(b)) {
                        absorbOrganisms(a, b);
                    } else if (b.shouldAbsorb(a)) {
                        absorbOrganisms(b, a);
                    } else {
                        // neither is predatory — merge by size (larger absorbs smaller)
                        Organism larger = a.size() >= b.size() ? a : b;
                        Organism smaller = larger == a ? b : a;
                        absorbOrganisms(larger, smaller);
                    }
                }
            }
        }
    }

    // bounding-sphere overlap test for organisms
    private boolean organismsTouching(Organism a, Organism b) {
        double dx = a.centerX() - b.centerX();
        double dy = a.centerY() - b.centerY();
        double dz = a.centerZ() - b.centerZ();
        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double threshold = Math.max(ORGANISM_TOUCH_DIST, a.boundingRadius() + b.boundingRadius());
        return dist <= threshold;
    }

    // Smooth absorption animation: larger absorbs smaller
    private void absorbOrganisms(Organism larger, Organism smaller) {
        if (!organisms.containsKey(larger.id) || !organisms.containsKey(smaller.id) || larger == smaller) return;

        log("Organism#" + larger.id + " absorbed Organism#" + smaller.id + ")");

        // animate smaller organism's group's nodes moving toward larger center and scaling down, then transfer cells
        Point3D target = new Point3D(larger.centerX(), larger.centerY(), larger.centerZ());
        Group smallGroup = smaller.group;

        // create a transition timeline per cell for smoothness
        ParallelTransition pt = new ParallelTransition();
        for (Cell c : new ArrayList<>(smaller.members())) {
            // compute world coords of cell
            Point3D world = c.worldPosition();
            // pick a target near larger center with slight random offset
            Point3D t = target.add((rng.nextDouble()-0.5)*larger.boundingRadius()*0.4,
                    (rng.nextDouble()-0.5)*larger.boundingRadius()*0.4,
                    (rng.nextDouble()-0.5)*larger.boundingRadius()*0.4);

            // animate Node transforms
            TranslateTransition tt = new TranslateTransition(Duration.seconds(1.2), c.node);
            tt.setToX(t.getX() - larger.group.getTranslateX());
            tt.setToY(t.getY() - larger.group.getTranslateY());
            tt.setToZ(t.getZ() - larger.group.getTranslateZ());

            ScaleTransition st = new ScaleTransition(Duration.seconds(1.2), c.node);
            st.setToX(0.8);
            st.setToY(0.8);
            st.setToZ(0.8);

            pt.getChildren().addAll(tt, st);
        }

        pt.setOnFinished(e -> {
            // after animation, re-parent cells and nodes from smaller to larger
            for (Cell c : new ArrayList<>(smaller.members())) {
                // remove from smaller
                smaller.removeCell(c);
                // reparent node visually into larger.group
                Platform.runLater(() -> {
                    // translate node coordinates to larger group's local coordinates
                    Point3D world = c.node.localToScene(Point3D.ZERO);
                    // remove from smaller group
                    smaller.group.getChildren().remove(c.node);
                    // add to larger group
                    larger.group.getChildren().add(c.node);
                    // set node translate to be relative to larger.group
                    c.node.setTranslateX(c.node.getTranslateX()); // already placed by transition relative to smaller; keep as-is
                });
                // logical reassign
                larger.addCell(c);
                c.organism = larger;
            }

            // remove smaller group from scene and organism map
            root3D.getChildren().remove(smallGroup);
            organisms.remove(smaller.id);
        });

        pt.play();
    }

    // ----------------- Logging -----------------
    private void log(String s) {
        Platform.runLater(() -> {
            logArea.appendText("[" + simDays + "] " + s + "\n");
            if (logArea.getText().length() > 20000) logArea.setText(logArea.getText().substring(8000));
        });
    }

    // ----------------- Inner classes -----------------

    // Simple 3D low-poly mesh factory (icosahedron-like)
    private MeshView createLowPolyCellMesh(Color color) {
        TriangleMesh mesh = new TriangleMesh();

        // Create a simple low-poly sphere-ish shape (icosahedron-ish)
        float t = (float)(1.0 / Math.sqrt(2));
        // vertices (approximate)
        float[] points = new float[] {
                0, 1, 0,
                0, -1, 0,
                -1, 0, 0,
                1, 0, 0,
                0, 0, 1,
                0, 0, -1
        };
        // scale points to radius
        for (int i = 0; i < points.length; i++) points[i] = points[i] * (float)CELL_BASE_RADIUS;

        mesh.getPoints().addAll(points);

        // dummy tex coords
        mesh.getTexCoords().addAll(0,0);

        // faces (triangles) by vertex indices
        int[] faces = new int[] {
                0,0,2,0,4,0,
                0,0,4,0,3,0,
                0,0,3,0,5,0,
                0,0,5,0,2,0,
                1,0,4,0,2,0,
                1,0,3,0,4,0,
                1,0,5,0,3,0,
                1,0,2,0,5,0
        };
        mesh.getFaces().addAll(faces);

        MeshView mv = new MeshView(mesh);
        PhongMaterial mat = new PhongMaterial(color);
        mat.setSpecularColor(Color.gray(0.9));
        mv.setMaterial(mat);
        return mv;
    }

    // -------------- Cell ----------------
    class Cell {
        final int id;
        volatile boolean alive = true;
        volatile double x, y, z;
        volatile double vx = 0, vy = 0, vz = 0;
        double energy = 40 + rng.nextDouble() * 60;
        int ageDays = 0;
        Mood mood = Mood.CALM;
        Organism organism;
        final Group node; // 3D node representing this cell (wraps MeshView)
        final MeshView mesh;
        final PhongMaterial material;

        Cell(int id, double x, double y, double z) {
            this.id = id;
            this.x = x; this.y = y; this.z = z;
            mesh = createLowPolyCellMesh(randomColor());
            material = (PhongMaterial) mesh.getMaterial();
            node = new Group(mesh);
            node.setTranslateX(x);
            node.setTranslateY(y);
            node.setTranslateZ(z);
            // small initial random rotation
            node.getTransforms().add(new Rotate(rng.nextDouble()*360, Rotate.Y_AXIS));
            // add click inspection
            node.setOnMouseClicked(ev -> {
                if (ev.getButton() == MouseButton.PRIMARY) {
                    log("Cell#" + id + " type: energy=" + String.format("%.1f", energy) + " mood=" + mood + " age=" + ageDays);
                }
            });
            // small initial velocity
            vx = (rng.nextDouble() - 0.5) * 40;
            vy = (rng.nextDouble() - 0.5) * 24;
            vz = (rng.nextDouble() - 0.5) * 40;
        }

        Color randomColor() {
            // pastel random
            return Color.hsb(rng.nextDouble() * 360, 0.45, 0.9);
        }

        // returns node's world position
        Point3D worldPosition() {
            return new Point3D(node.getTranslateX(), node.getTranslateY(), node.getTranslateZ());
        }

        void dayTick() {
            if (!alive) return;
            ageDays++;
            energy -= ENERGY_DECAY_PER_DAY;
            if (energy <= 0 || ageDays > 300 + rng.nextInt(200)) {
                die("old/energy");
                return;
            }
            decideMood();
            performMoodAction();
            // small color tint according to energy
            double frac = Math.max(0.15, Math.min(1.0, energy / MAX_ENERGY));
            Color base = (Color) material.getDiffuseColor();
            Color mixed = base.interpolate(Color.GRAY, 1 - frac);
            material.setDiffuseColor(mixed);
        }

        void decideMood() {
            double r = rng.nextDouble();
            if (energy < 20) mood = Mood.HUNGRY;
            else if (energy > 80 && r < 0.25) mood = Mood.DIVIDE;
            else if (r < 0.12) mood = Mood.SLEEPY;
            else if (r < 0.5) mood = Mood.CALM;
            else mood = Mood.ENERGETIC;
        }

        void performMoodAction() {
            switch (mood) {
                case CALM -> { energy = Math.min(MAX_ENERGY, energy + 0.6); }
                case HUNGRY -> { // seek food nearby: simplistic - bias random movement
                    vx += (rng.nextDouble() - 0.5) * 4;
                    vy += (rng.nextDouble() - 0.5) * 3;
                    vz += (rng.nextDouble() - 0.5) * 4;
                }
                case ENERGETIC -> { vx += (rng.nextDouble() - 0.5) * 6; vz += (rng.nextDouble() - 0.5) * 6; energy -= 1.0; }
                case SLEEPY -> { energy = Math.min(MAX_ENERGY, energy + 1.5); vx *= 0.8; vy *= 0.8; vz *= 0.8; }
                case DIVIDE -> {
                    if (energy > DIVIDE_THRESHOLD && rng.nextDouble() < DIVIDE_PROB) {
                        divide();
                    } else {
                        energy = Math.min(MAX_ENERGY, energy + 1.0);
                    }
                }
            }
        }

        void continuousUpdate(double seconds) {
            if (!alive) return;
            // move
            x += vx * seconds * WORLD_SCALE;
            y += vy * seconds * WORLD_SCALE;
            z += vz * seconds * WORLD_SCALE;

            // slight damping
            vx *= 0.992; vy *= 0.992; vz *= 0.992;

            // boundary bounce (cube world)
            double bound = 420;
            if (x < -bound) { x = -bound; vx = Math.abs(vx); }
            if (x > bound) { x = bound; vx = -Math.abs(vx); }
            if (y < -200) { y = -200; vy = Math.abs(vy); }
            if (y > 200) { y = 200; vy = -Math.abs(vy); }
            if (z < -bound) { z = -bound; vz = Math.abs(vz); }
            if (z > bound) { z = bound; vz = -Math.abs(vz); }

            // update node translation
            Platform.runLater(() -> {
                node.setTranslateX(x);
                node.setTranslateY(y);
                node.setTranslateZ(z);
            });
        }

        void divide() {
            if (energy <= DIVIDE_COST + 8) return;
            energy -= DIVIDE_COST;
            double angle = rng.nextDouble() * Math.PI * 2;
            double rx = x + Math.cos(angle) * (CELL_BASE_RADIUS * 3 + rng.nextDouble() * 10);
            double ry = y + (rng.nextDouble() - 0.5) * 20;
            double rz = z + Math.sin(angle) * (CELL_BASE_RADIUS * 3 + rng.nextDouble() * 10);
            Cell child = new Cell(cellIdGen.getAndIncrement(), rx, ry, rz);
            cells.add(child);
            // visual add to same organism group
            Organism parent = organism;
            if (parent != null) {
                parent.addCell(child);
                child.organism = parent;
                // small birth animation
                child.node.setScaleX(0.2); child.node.setScaleY(0.2); child.node.setScaleZ(0.2);
                parent.group.getChildren().add(child.node);
                ScaleTransition st = new ScaleTransition(Duration.seconds(0.9), child.node);
                st.setToX(1.0); st.setToY(1.0); st.setToZ(1.0);
                st.play();
            } else {
                // orphan cell: create new organism
                Organism o = new Organism(orgIdGen.getAndIncrement());
                organisms.put(o.id, o);
                o.addCell(child);
                child.organism = o;
                root3D.getChildren().add(o.group);
            }
            log("Cell#" + id + " divided -> Cell#" + child.id);
        }

        void die(String reason) {
            if (!alive) return;
            alive = false;
            // fade and remove visual
            Platform.runLater(() -> {
                FadeTransition ft = new FadeTransition(Duration.seconds(1.0), node);
                ft.setToValue(0.0);
                ft.setOnFinished(e -> {
                    if (organism != null) organism.group.getChildren().remove(node);
                    root3D.getChildren().remove(node);
                });
                ft.play();
            });
            if (organism != null) organism.removeCell(this);
        }
    }

    enum Mood { CALM, HUNGRY, ENERGETIC, DIVIDE, SLEEPY }

    // -------------- Organism --------------
    class Organism {
        final int id;
        final Set<Cell> members = Collections.synchronizedSet(new HashSet<>());
        final Group group = new Group(); // visual group for organism's cells
        double hormone = rng.nextDouble() * 0.12;
        boolean aggressive = false;

        Organism(int id) {
            this.id = id;
            // small label in 3D could be added; for now we keep group as container
        }

        void addCell(Cell c) {
            members.add(c);
            group.getChildren().add(c.node);
        }

        void removeCell(Cell c) {
            members.remove(c);
            Platform.runLater(() -> group.getChildren().remove(c.node));
            c.organism = null;
        }

        int size() { return (int) members.stream().filter(m -> m.alive).count(); }
        boolean isDead() { return members.stream().noneMatch(m -> m.alive); }

        double centerX() {
            return members.stream().filter(m -> m.alive).mapToDouble(m -> m.x).average().orElse(group.getTranslateX());
        }
        double centerY() {
            return members.stream().filter(m -> m.alive).mapToDouble(m -> m.y).average().orElse(group.getTranslateY());
        }
        double centerZ() {
            return members.stream().filter(m -> m.alive).mapToDouble(m -> m.z).average().orElse(group.getTranslateZ());
        }

        double boundingRadius() {
            // approximate bounding radius as max distance from center
            double cx = centerX(), cy = centerY(), cz = centerZ();
            double max = 20;
            for (Cell c : members) {
                double dx = c.x - cx, dy = c.y - cy, dz = c.z - cz;
                double d = Math.sqrt(dx*dx + dy*dy + dz*dz);
                if (d > max) max = d;
            }
            return Math.max(40, max);
        }

        void dailyUpdate() {
            // hormone dynamics & aggression
            double avgEnergy = members.stream().filter(m -> m.alive).mapToDouble(m -> m.energy).average().orElse(0.0);
            hormone += (rng.nextDouble() - 0.5) * 0.02;
            hormone += (1.0 - Math.tanh(size() / 6.0)) * 0.01;
            hormone += (avgEnergy / MAX_ENERGY - 0.5) * 0.02;
            hormone = Math.max(0.0, Math.min(1.0, hormone));

            // decide aggression
            aggressive = (avgEnergy < 30 && size() > 6 && rng.nextDouble() < 0.06);

            // reproduction wave if hormone high
            if (hormone > 0.6 && size() >= 3 && rng.nextDouble() < 0.18) {
                List<Cell> mem = new ArrayList<>(members);
                Collections.shuffle(mem);
                int shooters = Math.max(1, mem.size() / 3);
                for (int i = 0; i < shooters; i++) {
                    Cell c = mem.get(i);
                    c.mood = Mood.DIVIDE;
                    c.energy = Math.min(MAX_ENERGY, c.energy + 8);
                }
                hormone *= 0.4;
                log("Organism#" + id + " reproduction wave");
            }
        }

        // Should this organism try to absorb target?
        boolean shouldAbsorb(Organism target) {
            if (this == target) return false;
            // aggressive organisms prefer to absorb weaker, smaller organisms
            if (this.aggressive) return true;
            // otherwise if significantly larger, attempt absorption
            return this.size() >= target.size() * 1.6;
        }
    }

    // delta helper for mouse drag
    static class Delta { double x, y; }

}
