package com.sap.psr.vulas.java;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.validation.constraints.NotNull;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.sap.psr.vulas.FileAnalyzer;
import com.sap.psr.vulas.shared.json.model.Application;
import com.sap.psr.vulas.shared.json.model.Dependency;
import com.sap.psr.vulas.shared.util.FileSearch;


/**
 * Identifies all Java archives below a certain directory.
 * For each, a JarAnalyzer is created in order to analyze its content.
 */
public class JarAnalysisManager extends SimpleFileVisitor<Path> {

	private static final Log log = LogFactory.getLog(JarAnalysisManager.class);

	private ExecutorService pool;
	private int count = 0;
	private boolean instrument = false;
	private Application ctx = null;
	private Path workDir = null;
	private Path libDir = null;
	private Path inclDir = null;
	private Map<Path, JarAnalyzer> analyzers = new HashMap<Path,JarAnalyzer>();
	private boolean rename = false;
	private Map<Path,Dependency> mavenDeps = null;

	public JarAnalysisManager(int _pool_size, boolean _instr, Application _ctx) {
		this.pool = Executors.newFixedThreadPool(_pool_size);
		this.instrument = _instr;
		this.ctx = _ctx;
	}

	public void setInstrument(boolean _instr) {
		this.instrument = _instr;
	}

	public void setWorkDir(Path _p) { this.setWorkDir(_p, false); }

	public void setWorkDir(Path _p, boolean _create) {
		this.workDir = _p;
		if(_create) {
			try {
				Files.createDirectories(_p);
			} catch (IOException e) {
				JarAnalysisManager.log.error("Error while creating dir [" + _p + "]: " + e.getMessage());
			}
		}
		JarAnalysisManager.log.info("Work dir set to [" + _p + "]");
	}

	public void setLibDir(Path _p) {
		this.libDir = _p;
		JarAnalysisManager.log.info("Lib dir set to [" + _p + "]");
	}

	public void setIncludeDir(Path _p) {
		this.inclDir = _p;
		JarAnalysisManager.log.info("Include dir set to [" + _p + "]");
	}

	/**
	 * Determines whether the instrumented JAR is renamed or not. If yes, the new file name follows the following format:
	 * - If app context is provided: [originalJarName]-vulas-[appGroupId]-[appArtifactId]-[appVersion].jar
	 * - Otherwise: [originalJarName]-vulas.jar
	 * @param boolean
	 */
	public void setRename(boolean _b) { this.rename = _b; }

	/**
	 * Takes a map of file system paths to {@link Dependency}s.
	 * Entries will be used when instantiating {@link JarAnalyzer}s in {@link #startAnalysis(Set, JarAnalyzer)}.
	 */
	public void setMavenDependencies(Map<Path, Dependency> _deps) {
		this.mavenDeps = _deps;
	}

	/**
	 * Returns the {@link Dependency} for a given JAR path, or null if no such path is known.
	 * The underlying map is built during the execution of the Maven plugin.
	 * @param _p
	 */
	public Dependency getMavenDependency(Path _p) {
		if(this.mavenDeps!=null)
			return this.mavenDeps.get(_p);
		else 
			return null;
	}
	
	/**
	 * Starts the analysis for all {@link Path}s of the given {@link Map} that have a null value.
	 * As such, it can be used to avoid analyzing archives multiple times.
	 */
	public void startAnalysis(@NotNull Map<Path, JarAnalyzer> _paths, JarAnalyzer _parent) {
		
		// Split those that have been analyzed already and those that need analysis
		final Set<Path> not_yet_analyzed = new HashSet<Path>();
		for(Map.Entry<Path, JarAnalyzer> entry: _paths.entrySet()) {
			if(entry.getValue()==null)
				not_yet_analyzed.add(entry.getKey());
			else
				this.analyzers.put(entry.getKey(), entry.getValue());
		}
		
		// Analyze if necessary
		if(!not_yet_analyzed.isEmpty()) {
			log.info("[" + this.analyzers.size() + "/" + _paths.size() + "] archives already analyzed, the remaining [" + not_yet_analyzed.size() + "] will be analyzed now ...");
			this.startAnalysis(not_yet_analyzed, _parent);
		} else {
			log.info("All [" + this.analyzers.size() + "/" + _paths.size() + "] archives have been analyzed already");
		}		
	}

	/**
	 * Starts the analysis for all the given {@link Path}s, which must point to either JAR or WAR archives.
	 */
	public void startAnalysis(@NotNull Set<Path> _paths, JarAnalyzer parent) {

		// Set the several static attributes of the JarAnalyzer, for all the instances created later
		JarAnalyzer.setAppContext(this.ctx);

		// Add all the paths to the classpath, so that the compilation works (if instrumentation is requested).
		for(Path p: _paths) {
			try {
				JarAnalyzer.insertClasspath(p.toString());
			} catch (Exception e) {
				// No problem at all if instrumentation is not requested.
				// If instrumentation is requested, however, some classes may not compile
				JarAnalysisManager.log.error("Error while updating the classpath: " + e.getMessage());
			}
		}

		// Add additional JARs into the classpath (if any)
		if(this.libDir!=null && (this.libDir.toFile().exists())) {
			final FileSearch vis = new FileSearch(new String[] {"jar"});
			final Set<Path> libs = vis.search(this.libDir);
			for(Path p: libs) {
				try {
					JarAnalyzer.insertClasspath(p.toString());
				} catch (Exception e) {
					// No problem at all if instrumentation is not requested.
					// If instrumentation is requested, however, some classes may not compile
					JarAnalysisManager.log.error("Error while updating the classpath from lib [" + this.libDir + "]: " + e.getMessage());
				}
			}
		}

		// Create temp directory for storing the modified JARs (if any)
		if(this.instrument && this.workDir==null) {
			try {
				workDir = java.nio.file.Files.createTempDirectory("jar_analysis_");
			} catch (IOException e) {
				throw new IllegalStateException("Unable to create work directory", e);
			}
		}

		// Create a JarAnalyzer for all paths, and ask the thread pool to start them
		this.count = 0;
		for(Path p: _paths) {
			try {
				this.count++;
				JarAnalyzer ja = null;
				if(p.toString().endsWith("jar")) {
					ja = new JarAnalyzer();
					ja.analyze(p.toFile());
					ja.setInstrument(this.instrument);
				}
				else if(p.toString().endsWith("war")) {
					ja = new WarAnalyzer();
					ja.analyze(p.toFile());
					ja.setInstrument(this.instrument);
					((WarAnalyzer)ja).setIncludeDir(this.inclDir);
				} 
				else if(p.toString().endsWith("aar")) {
					ja = new AarAnalyzer();
					ja.analyze(p.toFile());
					ja.setInstrument(this.instrument);
				} 
				else {
					JarAnalysisManager.log.warn("File extension not supported (only JAR, WAR, AAR): " + p);
					continue;
				}
				if(parent!=null)
					ja.setParent(parent);

				ja.setRename(this.rename);
				ja.setWorkDir(this.workDir);

				if(this.getMavenDependency(p)!=null)
					ja.setLibraryId(this.getMavenDependency(p).getLib().getLibraryId());

				this.analyzers.put(p, ja);
				this.pool.execute(ja);
			} catch (Exception e) {
				JarAnalysisManager.log.error("Error while analyzing path [" + p + "]: " + e.getMessage());
			}
		}

		// Once we're all through, let's wait for the thread pool to finish the work
		this.pool.shutdown();
		try {
			while (!this.pool.awaitTermination(10, TimeUnit.SECONDS))
				JarAnalysisManager.log.info("Wait for the completion of analysis threads ...");
		} catch (InterruptedException e) {
			JarAnalysisManager.log.error("Interrupt exception");
		}

		JarAnalysisManager.log.info("A total of [" + this.count + "] archives analyzed");
	}

	public Set<JarAnalyzer> getAnalyzers() {
		final HashSet<JarAnalyzer> analyzers = new HashSet<JarAnalyzer>();
		for(JarAnalyzer ja : this.analyzers.values()) {
			analyzers.add(ja);
			if(ja.hasChilds()) {
				final Set<FileAnalyzer> fas = ja.getChilds(true);
				for(FileAnalyzer fa: fas)
					if(fa instanceof JarAnalyzer)
						analyzers.add((JarAnalyzer)fa);
			}
		}
		return analyzers;
	}

	/**
	 * Returns the analyzer used to analyze a Java archive whose path ends with the given sub-path _p (the first match is taken), null if no such analyzer can be found.
	 * @param _p
	 * @return
	 */
	public JarAnalyzer getAnalyzerForSubpath(Path _p) {
		JarAnalyzer ja = null;
		for(Path p: this.analyzers.keySet()) {
			if(p.endsWith(_p)) {
				ja = this.analyzers.get(p); 
				break;
			}
		}
		return ja;
	}
}
