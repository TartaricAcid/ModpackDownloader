package harystolho.mpd;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HttpsURLConnection;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import harystolho.mpd.controller.MainController;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;

public class DownloadUtils {

	private static Thread downloadThread;
	private String modpackDownloadURL;

	private MainController controller;

	public DownloadUtils(MainController controller) {
		this.controller = controller;
	}

	public File createDownloadDir(String url) {
		File downloadDir;

		url = url.split("projects/")[1];

		if (Main.configs.getProperty("downloadFolder").equals("/")) {
			downloadDir = new File(url);
		} else {
			downloadDir = new File(Main.configs.getProperty("downloadFolder") + "/" + url);
		}

		if (!downloadDir.exists()) {
			System.out.println(downloadDir.getAbsolutePath());
			downloadDir.mkdir();
		}

		return downloadDir;
	}

	public StringBuilder loadModsJSON(File downloadDir) {
		StringBuilder sb = new StringBuilder();
		try {
			for (String s : Files.readAllLines(Paths.get(downloadDir.getPath() + "/manifest.json"))) {
				sb.append(s);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return sb;
	}

	public void downloadModpack(String url) {
		File downloadDir = createDownloadDir(url);

		System.out.println(downloadDir);

		downloadThread = new Thread(new Runnable() {
			@Override
			public void run() {

				if (modpackDownloadURL == null) {
					controller.addText("Click the 'getInfo' button first");
					return;
				}

				Path modpackDir = Paths.get(downloadDir.getPath() + "/" + controller.modpackID + ".zip");

				if (!checkIfAlreadyDownloaded(downloadDir)) {
					downloadModpackConfigs(modpackDir);
					unZipModpackConfigs(modpackDir, downloadDir);
				}

				downloadMods(downloadDir);
			}

			private void downloadMods(File downloadDir) {
				controller.addText("Downloading mod list.");

				controller.setArchPorcentage(0);

				// make sure there is a /mods/ folder
				File modsFolder = new File(downloadDir + "/overrides/mods");
				if (!modsFolder.exists()) {
					modsFolder.mkdir();
				}

				StringBuilder sb = loadModsJSON(downloadDir);

				JSONObject js = new JSONObject(sb.toString());

				JSONArray modList = new JSONArray(js.get("files").toString());

				List<Future<Boolean>> result = new ArrayList<>();

				double UniqueModPorcentage = 100.0 / modList.length();
				double totalPorcentage = 0;

				if (controller.getModsToDownload() != null) {
					UniqueModPorcentage = 100.0 / controller.getModsToDownload().size();
				}

				controller.addText("Downloading mods...");

				for (int x = 0; x < modList.length(); x++) {
					JSONObject json = new JSONObject(modList.get(x).toString());
					if (controller.getModsToDownload() != null) {
						if (controller.getModsToDownload().contains(json.get("projectID"))) {
							result.add(downloadMod(json, x, modList.length(), downloadDir.toPath()));
						}
					} else {
						result.add(downloadMod(json, x, modList.length(), downloadDir.toPath()));
					}

				}
				int count = 0;
				for (Future<Boolean> f : result) {
					try {
						if (f.get()) {
							totalPorcentage += UniqueModPorcentage;
							controller.setArchPorcentage(totalPorcentage);
							controller.setNumberPorcentage(totalPorcentage);
							count++;
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (ExecutionException e) {
						e.printStackTrace();
					}
				}

				if (controller.getModsToDownload() != null) {
					controller.addText(
							"\nDownloaded " + count + " out of " + controller.getModsToDownload().size() + " Mods");
				} else {
					controller.addText("\nDownloaded " + count + " out of " + modList.length() + " Mods");
				}

				// Display recommended forge version
				JSONObject forgeID = new JSONObject(
						new JSONArray(new JSONObject(js.get("minecraft").toString()).get("modLoaders").toString())
								.get(0).toString());
				controller.addText("Forge version: " + forgeID.get("id"));
				controller.addText("Finished download");
				controller.addText("");
			}

		});

		downloadThread.start();
	}

	public void downloadModpackConfigs(Path modpackDir) {
		HttpsURLConnection conn;
		try {
			conn = (HttpsURLConnection) new URL("https://minecraft.curseforge.com" + modpackDownloadURL + "/download")
					.openConnection();
			controller.addText("Starting download: " + modpackDownloadURL.split("/")[2].toUpperCase());

			try (InputStream in = conn.getInputStream()) {
				Files.copy(in, modpackDir, StandardCopyOption.REPLACE_EXISTING);
			}
			conn.disconnect();
		} catch (IOException e) {
			controller.addText("Couldn't download the modpack configs.");
		}

	}

	public void unZipModpackConfigs(Path modpackDir, File downloadDir) {
		ZipInputStream zis;
		try {
			byte[] b = new byte[1024];

			zis = new ZipInputStream(new FileInputStream(modpackDir.toFile()));
			ZipEntry ze = zis.getNextEntry();

			controller.addText("Unziping configs");
			while (ze != null) {
				String fileName = ze.getName();
				File newFile = new File(downloadDir.getPath() + "/" + fileName);

				// create parent folder if it doesn't exist
				new File(newFile.getParent()).mkdir();

				if (!ze.isDirectory()) {
					newFile.createNewFile();
				}

				// if it's a file create it, if it it's a folder skip
				if (newFile.isFile()) {
					FileOutputStream fos = new FileOutputStream(newFile);
					int len;

					while ((len = zis.read(b)) > 0) {
						fos.write(b, 0, len);
					}

					fos.close();
				}

				ze = zis.getNextEntry();
			}

			zis.closeEntry();
			zis.close();
		} catch (IOException e) {
			controller.addText("Couldn't unzip the modpack configs.");
		}

		// Deletes the configs folder
		try {
			Files.deleteIfExists(modpackDir);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public Future<Boolean> downloadMod(JSONObject json, int number, int total, Path dir) {
		return Main.ex.submit(new Callable<Boolean>() {
			@Override
			public Boolean call() throws Exception {
				String url = "https://minecraft.curseforge.com/projects/" + json.get("projectID") + "/files/"
						+ json.get("fileID");
				controller.addText("\nDownloading " + (number + 1) + "/" + total + "\n" + url);
				HttpsURLConnection conn = (HttpsURLConnection) new URL(url + "/download").openConnection();

				try (InputStream is = conn.getInputStream()) {
					Files.copy(is, Paths.get(dir + "/overrides/mods/" + json.get("projectID") + ".jar"),
							StandardCopyOption.REPLACE_EXISTING);
				} catch (NoSuchFileException exc) {
					controller.addText("Couldn't downloader mod " + json.get("projectID"));
				} catch (IOException ecx) {
					controller.addText(ecx.getMessage());
				} finally {
					conn.disconnect();
				}
				return true;
			}
		});
	}

	public void getInfo(String url) {
		controller.addText(controller.getLoader().getResources().getString("mpd.modpackURL") + ": " + url);
		try {

			Future<Document> futureName = Main.ex.submit(new Callable<Document>() {
				@Override
				public Document call() throws Exception {
					return Jsoup.connect(url).get();
				}
			});

			Future<Document> futureVersion = Main.ex.submit(new Callable<Document>() {
				@Override
				public Document call() throws Exception {
					return Jsoup.connect(url + "/files").get();
				}
			});

			Future<Integer> futureModNum = Main.ex.submit(new Callable<Integer>() {
				@Override
				public Integer call() throws Exception {
					return getModpackMods(url);
				}
			});

			String modpackName = futureName.get().select("span.overflow-tip").html();
			if (modpackName.length() == 0) {
				controller.addText(controller.getLoader().getResources().getString("mpd.invalidModpack"));
				return;
			}

			modpackDownloadURL = futureName.get().select(
					"ul.cf-recentfiles:nth-child(2) > li:nth-child(1) > div:nth-child(2) > div:nth-child(1) > a:nth-child(1)")
					.attr("href");

			int mods = futureModNum.get();

			String version = futureVersion.get()
					.select("tr.project-file-list-item:nth-child(1) > td:nth-child(5) > span:nth-child(1)").text();

			controller.modpackID = futureVersion.get().select(
					"tr.project-file-list-item:nth-child(1) > td:nth-child(2) > div:nth-child(1) > div:nth-child(1) > a:nth-child(1)")
					.get(0).attr("href").split("files/")[1];

			controller.setInfo(modpackName, mods, version);
		} catch (IllegalArgumentException e) {
			controller.addText("Must supply a valid URL");
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			controller.addText("Invalid url");
		}

	}

	public static int getModpackMods(String url) {
		try {
			Document doc = Jsoup.connect(url + "/relations/dependencies?page=1").get();
			int size = doc.select("div.b-pagination:nth-child(2) > ul:nth-child(1)").get(0).children().size();
			int modNumber = Integer.parseInt(doc.select("div.b-pagination:nth-child(2) > ul:nth-child(1)").get(0)
					.child(size - 2).outerHtml().split("page=")[1].split("\"")[0], 10);

			Document doc2 = Jsoup.connect(url + "/relations/dependencies?page=" + modNumber).get();
			int plusMods = doc2.select(".listing").get(0).children().size();

			modNumber = (modNumber - 1) * 20 + plusMods;
			return modNumber;
		} catch (HttpStatusException e) {
			return 0;
		} catch (IOException e) {
			e.printStackTrace();
		} catch (IndexOutOfBoundsException e) {
			return 0;
		}
		return 0;
	}

	public boolean checkIfAlreadyDownloaded(File downloadDir) {
		File manifest = new File(downloadDir.getPath() + "/manifest.json");
		if (manifest.exists()) {
			return true;
		} else {
			return false;
		}

	}

}
