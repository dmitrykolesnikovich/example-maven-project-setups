package tests;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.stjs.generator.ClassWithJavascript;
import org.stjs.generator.GenerationDirectory;
import org.stjs.generator.Generator;
import org.stjs.generator.GeneratorConfiguration;
import org.stjs.generator.GeneratorConfigurationBuilder;
import org.stjs.javascript.Global;

import com.gargoylesoftware.htmlunit.AlertHandler;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import a.b.c.DTO;
import a.b.c.Global2;
import a.b.c.Main;
import utils.Maven;

/**
 * https://github.com/st-js/st-js/blob/master/maven-plugin/src/main/java/org/stjs/maven/AbstractSTJSMojo.java#L199
 *
 * @author jjYBdx4IL
 */
public class RunGeneratorTest extends AbstractHandler {

	private static final Logger LOG = LoggerFactory.getLogger(RunGeneratorTest.class);

	private final File baseDir = new File(Maven.getBasedir(RunGeneratorTest.class));
	private final File sourceFolder = new File(baseDir, "src/main/java");
	private final File tempFolder = new File(baseDir, "target/" + RunGeneratorTest.class.getName());
	private Server server = new Server(0);
	private final List<String> jsFileNames = new ArrayList<>();

	@After
	public void after() throws Exception {
		if (server != null) {
			server.stop();
			server = null;
		}
	}

	@Before
	public void before() throws Exception {
		tempFolder.mkdirs();
		FileUtils.cleanDirectory(tempFolder);
		
		jsFileNames.clear();
		jsFileNames.add("stjs.js");
		jsFileNames.add("a/b/c/Global2.js");
		jsFileNames.add("a/b/c/DTO.js");
		jsFileNames.add("a/b/c/Main.js");

		server.setHandler(this);
		server.start();
	}

	@Test
	public void test() throws Exception {
		GeneratorConfigurationBuilder configBuilder = new GeneratorConfigurationBuilder();
		configBuilder.allowedPackage("a.b.c");
		configBuilder.allowedPackage("org.stjs.javascript");
		configBuilder.stjsClassLoader(getClass().getClassLoader());
		configBuilder.targetFolder(Paths.get(baseDir.getAbsolutePath(), "target/classes").toFile());
		GenerationDirectory gendir = new GenerationDirectory(tempFolder, null, new URI("/jsoutput"));
		configBuilder.generationFolder(gendir);

		GeneratorConfiguration configuration = configBuilder.build();
		Generator generator = new Generator(configuration);

		// measure generation time in hot state
		ClassWithJavascript stjsClass = generator.generateJavascript(Main.class.getName(), sourceFolder);
		long durationMs = -System.currentTimeMillis();
		stjsClass = generator.generateJavascript(Main.class.getName(), sourceFolder);
		durationMs += System.currentTimeMillis();

		ClassWithJavascript stjsClass2 = generator.generateJavascript(DTO.class.getName(), sourceFolder);
		ClassWithJavascript stjsClass3 = generator.generateJavascript(Global2.class.getName(), sourceFolder);
		ClassWithJavascript stjsClass4 = generator.generateJavascript(Global.class.getName(), sourceFolder);
		
		LOG.info("js generation time (hot, ms): " + durationMs);

		List<URI> outputFiles = new ArrayList<>(stjsClass2.getJavascriptFiles());
		outputFiles.addAll(stjsClass3.getJavascriptFiles());
		outputFiles.addAll(stjsClass4.getJavascriptFiles());
		outputFiles.addAll(stjsClass.getJavascriptFiles());
		// assertEquals(2, outputFiles.size());

		// run the generated js
		ScriptEngineManager manager = new ScriptEngineManager();
		ScriptEngine engine = manager.getEngineByName("javascript");

		generator.copyJavascriptSupport(tempFolder);
		
		
		final CountDownLatch countDown = new CountDownLatch(1);
		
		try (final WebClient webClient = new WebClient()) {
			webClient.getOptions().setJavaScriptEnabled(true);
			webClient.getOptions().setThrowExceptionOnScriptError(false);
			webClient.setAlertHandler(new AlertHandler() {
				
				@Override
				public void handleAlert(Page arg0, String arg1) {
					LOG.info("alert: " + arg1);
					if ("test log alert".equals(arg1)) {
						countDown.countDown();
					}
				}
			});

			final HtmlPage page = webClient.getPage(getUrl("/"));
			LOG.info("page: " + page.asXml());
		}
		
		assertTrue(countDown.await(10, TimeUnit.SECONDS));
	}
	
	public URL getUrl(String path) throws MalformedURLException, UnknownHostException {
		ServerConnector connector = (ServerConnector) server.getConnectors()[0];
		InetAddress addr = InetAddress.getLocalHost();
		return new URL(String.format(Locale.ROOT, "%s://%s:%d%s", "http", addr.getHostAddress(),
				connector.getLocalPort(), path));
	}

	@Override
	public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {
		LOG.info(String.format(Locale.ROOT, "handle(%s, ...)", target));

		if ("/".equals(target)) {
			response.setStatus(HttpServletResponse.SC_OK);
			response.setContentType("text/html");
			response.getWriter().print("<!DOCTYPE html><html><head>");
			for (String jsFileName : jsFileNames) {
				response.getWriter().print("<script type=\"text/javascript\" src=\"" + jsFileName + "\"></script>");
			}
			response.getWriter().print("</head><body>" + "</body></html>");
		} else {
			try {
				File jsFile = new File(tempFolder, target.substring(1));
				LOG.info("serving file: " + jsFile.getAbsolutePath());
				String js = IOUtils.toString(jsFile.toURI(), "UTF-8");
				response.setStatus(HttpServletResponse.SC_OK);
				response.setContentType("application/javascript");
				response.getWriter().print(js);
			} catch (IOException ex) {
				response.setStatus(HttpServletResponse.SC_NOT_FOUND);
				response.setContentType("text/html");
			}
		}
		baseRequest.setHandled(true);
	}	
}
