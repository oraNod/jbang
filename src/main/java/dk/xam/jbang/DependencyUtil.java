package dk.xam.jbang;

import static dk.xam.jbang.Settings.CP_SEPARATOR;
import static dk.xam.jbang.Util.*;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.jboss.shrinkwrap.resolver.api.maven.ConfigurableMavenResolverSystem;
import org.jboss.shrinkwrap.resolver.api.maven.Maven;
import org.jboss.shrinkwrap.resolver.api.maven.PackagingType;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinate;
import org.jboss.shrinkwrap.resolver.api.maven.coordinate.MavenCoordinates;

class DependencyUtil {
	/**
	 * 
	 * @param depIds
	 * @param customRepos
	 * @param loggingEnabled
	 * @return string with resolved classpath
	 */
	public String resolveDependencies(List<String> depIds, List<MavenRepo> customRepos,
			boolean loggingEnabled) {

		// if no dependencies were provided we stop here
		if (depIds.isEmpty()) {
			return "";
		}

		String depsHash = String.join(CP_SEPARATOR, depIds);
		Map<String, String> cache = Collections.emptyMap();
		// Use cached classpath from previous run if present if
		// TODO: does not handle spaces in ~/.m2 folder.
		if (Settings.getCacheDependencyFile().isFile()) {
			try {

				cache = Files	.readAllLines(Settings.getCacheDependencyFile().toPath())
								.stream()
								.filter(it -> !it.trim().isEmpty())
								.collect(Collectors.toMap(
										it -> it.split(" ")[0],
										it -> it.split(" ")[1],
										(k1, k2) -> {
											return k2;
										} // in case of duplicates, last one wins
								));
			} catch (IOException e) {
				warnMsg("Could not access cache " + e.getMessage());
			}

			if (cache.containsKey(depsHash)) {
				String cachedCP = cache.get(depsHash);

				// Make sure that local dependencies have not been wiped since resolving them
				// (like by deleting .m2)
				boolean allExists = Arrays.stream(cachedCP.split(CP_SEPARATOR)).allMatch(it -> new File(it).exists());
				if (allExists) {
					return cachedCP;
				} else {
					warnMsg("Detected missing dependencies in cache.");
				}
			}
		}

		if (loggingEnabled) {
			infoMsg("Resolving dependencies...");
		}

		try {
			List<File> artifacts = resolveDependenciesViaAether(depIds, customRepos, loggingEnabled);
			String classPath = artifacts.stream()
										.map(it -> it.getAbsolutePath())
										.map(it -> it.contains(" ") ? '"' + it + '"' : it)
										.collect(Collectors.joining(CP_SEPARATOR));

			if (loggingEnabled) {
				infoMsg("Dependencies resolved");
			}

			// Add classpath to cache
			try {
				// Open given file in append mode.
				try (BufferedWriter out = new BufferedWriter(new FileWriter(Settings.getCacheDependencyFile(), true))) {
					out.write(depsHash + " " + classPath + "\n");
				}
			} catch (IOException e) {
				errorMsg("Could not write to cache:" + e.getMessage(), e);
			}

			// Print the classpath
			return classPath;
		} catch (DependencyException e) { // Probably a wrapped Nullpointer from
											// 'DefaultRepositorySystem.resolveDependencies()', this however is probably
											// a connection problem.
			errorMsg("Exception: " + e.getMessage());
			throw new ExitException(0,
					"Failed while connecting to the server. Check the connection (http/https, port, proxy, credentials, etc.) of your maven dependency locators.",
					e);
		}
	}

	public List<File> resolveDependenciesViaAether(List<String> depIds, List<MavenRepo> customRepos,
			boolean loggingEnabled) {

		if (customRepos.isEmpty()) {
			customRepos = Arrays.asList(toMavenRepo("jcenter"));
		}

		ConfigurableMavenResolverSystem resolver = Maven.configureResolver()
														.withMavenCentralRepo(false);

		customRepos.stream().forEach(mavenRepo -> {
			mavenRepo.apply(resolver);
		});

		System.setProperty("maven.repo.local", Settings.getLocalMavenRepo().toPath().toAbsolutePath().toString());

		return depIds.stream().flatMap(it -> {

			if (loggingEnabled)
				System.err.print(String.format("[jbang]     Resolving %s...", it));

			List<File> artifacts;
			try {
				artifacts = resolver.resolve(depIdToArtifact(it).toCanonicalForm())
									.withTransitivity()
									.asList(File.class); // , RUNTIME);
			} catch (RuntimeException e) {
				throw new ExitException(1, "Could not resolve dependency", e);
			}

			if (loggingEnabled)
				System.err.println("Done");

			return artifacts.stream();
		}).collect(Collectors.toList());
	}

	public String decodeEnv(String value) {
		if (value.startsWith("{{") && value.endsWith("}}")) {
			String envKey = value.substring(2, value.length() - 2);
			String envValue = System.getenv(envKey);
			if (null == envValue) {
				throw new IllegalStateException(String.format(
						"Could not resolve environment variable {{%s}} in maven repository credentials", envKey));
			}
			return envValue;
		} else {
			return value;
		}
	}

	public MavenCoordinate depIdToArtifact(String depId) {

		Pattern gavPattern = Pattern.compile(
				"^(?<groupid>[^:]*):(?<artifactid>[^:]*):(?<version>[^:@]*)(:(?<classifier>[^@]*))?(@(?<type>.*))?$");
		Matcher gav = gavPattern.matcher(depId);
		gav.find();

		if (!gav.matches()) {
			throw new IllegalStateException(String.format(
					"[ERROR] Invalid dependency locator: '%s'.  Expected format is groupId:artifactId:version[:classifier][@type]",
					depId));
		}

		String groupId = gav.group("groupid");
		String artifactId = gav.group("artifactid");
		String version = formatVersion(gav.group("version"));
		String classifier = gav.group("classifier");
		String type = Optional.ofNullable(gav.group("type")).orElse("jar");

		// String groupId, String artifactId, String classifier, String extension,
		// String version
		// String groupId, String artifactId, String version, String scope, String type,
		// String classifier, ArtifactHandler artifactHandler

		// shrinkwrap format: groupId:artifactId:[packagingType:[classifier]]:version

		return MavenCoordinates.createCoordinate(groupId, artifactId, version, PackagingType.of(type), classifier);
	}

	public String formatVersion(String version) {
		// replace + with open version range for maven
		if (version.endsWith("+")) {
			return "[" + removeLastCharOptional(version) + ",)";
		} else {
			return version;
		}
	}

	public static String removeLastCharOptional(String s) {
		return Optional	.ofNullable(s)
						.filter(str -> str.length() != 0)
						.map(str -> str.substring(0, str.length() - 1))
						.orElse(s);
	}

	static public MavenRepo toMavenRepo(String repoReference) {
		String[] split = repoReference.split("=");
		String reporef = null;
		String repoid = null;

		if (split.length == 1) {
			reporef = split[0];
		} else if (split.length == 2) {
			repoid = split[0];
			reporef = split[1];
		} else {
			throw new IllegalStateException("Invalid Maven repository reference: " + repoReference);
		}

		if ("jcenter".equalsIgnoreCase(reporef)) {
			return new MavenRepo(Optional.ofNullable(repoid).orElse("jcenter"), "https://jcenter.bintray.com/");
		} else if ("google".equalsIgnoreCase(reporef)) {
			return new MavenRepo(Optional.ofNullable(repoid).orElse("google"), "https://maven.google.com/");
		} else if ("mavenCentral".equalsIgnoreCase(reporef)) {
			return new MavenRepo("", "") {
				@Override
				public void apply(ConfigurableMavenResolverSystem resolver) {
					resolver.withMavenCentralRepo(true);
				}
			};
		} else {
			return new MavenRepo(repoid, reporef);
		}
	}
}