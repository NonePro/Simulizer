package simulizer.ui.layout;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import simulizer.ui.WindowManager;
import simulizer.ui.interfaces.InternalWindow;
import simulizer.ui.interfaces.WindowEnum;

public class Layouts implements Iterable<Layout> {
	// TODO: Find a way to scale to window size

	private final Path folder = Paths.get("layouts");
	private Set<Layout> layouts = new HashSet<Layout>();
	private WindowManager wm;

	private static final String DEFAULT_LAYOUT = "default.json";

	public Layouts(WindowManager wm) {
		this.wm = wm;
		reload(true);
	}

	public void reload(boolean findDefault) {
		layouts.clear();
		Gson g = new Gson();

		Layout defaultLayout = null;
		try {
			// Check all files in the layouts folder
			for (Path layout : Files.newDirectoryStream(folder)) {
				// Ignore subfolders
				if (layout.toFile().isFile()) {
					try {
						Layout l = g.fromJson(new JsonReader(Files.newBufferedReader(layout)), Layout.class);
						if (layout.toFile().getName().equals(DEFAULT_LAYOUT)) defaultLayout = l;
						layouts.add(l);
					} catch (JsonIOException | JsonSyntaxException | IOException e) {
						System.out.println("Invalid File: " + layout.toUri().toString());
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		if (defaultLayout != null && findDefault) wm.setLayout(defaultLayout);
	}

	public void saveLayout(File saveFile) {
		List<InternalWindow> openWindows = wm.getOpenWindows();
		WindowLocation[] wls = new WindowLocation[openWindows.size()];
		for (int i = 0; i < wls.length; i++) {
			InternalWindow window = openWindows.get(i);
			wls[i] = new WindowLocation(WindowEnum.toEnum(window), window.getLayoutX(), window.getLayoutY(), window.getWidth(), window.getHeight());
		}
		Layout l = new Layout(saveFile.getName(), wm.getPane().getWidth(), wm.getPane().getHeight(), wls);
		Gson g = new GsonBuilder().setPrettyPrinting().create();
		try {
			// Thanks to: http://stackoverflow.com/questions/7366266/best-way-to-write-string-to-file-using-java-nio#answer-21982658
			Files.write(Paths.get(saveFile.toURI()), g.toJson(l).getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		} catch (IOException e) {
			System.err.println("Unable to save file");
		}
	}

	@Override
	public Iterator<Layout> iterator() {
		return layouts.iterator();
	}
}
