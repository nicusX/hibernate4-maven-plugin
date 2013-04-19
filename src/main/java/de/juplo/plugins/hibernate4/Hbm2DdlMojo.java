package de.juplo.plugins.hibernate4;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import javax.persistence.Embeddable;
import javax.persistence.Entity;
import javax.persistence.MappedSuperclass;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.NamingStrategy;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.hbm2ddl.SchemaExport.Type;
import org.hibernate.tool.hbm2ddl.Target;
import org.scannotation.AnnotationDB;

import com.pyx4j.log4j.MavenLogAppender;


/**
 * Goal which extracts the hibernate-mapping-configuration and
 * exports an according SQL-database-schema.
 *
 * @goal export
 * @phase process-classes
 * @threadSafe
 * @requiresDependencyResolution runtime
 */
public class Hbm2DdlMojo extends AbstractMojo
{
  public final static String EXPORT_SKIPPED_PROPERTY = "hibernate.export.skipped";

  public final static String DRIVER_CLASS = "hibernate.connection.driver_class";
  public final static String URL = "hibernate.connection.url";
  public final static String USERNAME = "hibernate.connection.username";
  public final static String PASSWORD = "hibernate.connection.password";
  public final static String DIALECT = "hibernate.dialect";
  // [nicus]
  public final static String NAMING_STRATEGY="hibernate.ejb.naming_strategy";

  private final static String MD5S = "schema.md5s";

  /**
   * The maven project.
   * <p>
   * Only needed internally.
   *
   * @parameter property="project"
   * @required
   * @readonly
   */
  private MavenProject project;

  /**
   * Build-directory.
   * <p>
   * Only needed internally.
   *
   * @parameter property="project.build.directory"
   * @required
   * @readonly
   */
  private String buildDirectory;

  /**
   * Classes-Directory to scan.
   * <p>
   * This parameter defaults to the maven build-output-directory for classes.
   * Additonally, all dependencies are scanned for annotated classes.
   *
   * @parameter property="project.build.outputDirectory"
   */
  private String outputDirectory;

  /**
   * Wether to scan test-classes too, or not.
   * <p>
   * If this parameter is set to <code>true</code> the test-classes of the
   * artifact will be scanned for hibernate-annotated classes additionally.
   *
   * @parameter property="hibernate.export.scan_testclasses" default-value="false"
   */
  private boolean scanTestClasses;

  /**
   * Test-Classes-Directory to scan.
   * <p>
   * This parameter defaults to the maven build-output-directory for
   * test-classes.
   * <p>
   * This parameter is only used, when <code>scanTestClasses</code> is set
   * to <code>true</code>!
   *
   * @parameter property="project.build.testOutputDirectory"
   */
  private String testOutputDirectory;

  /**
   * Skip execution
   * <p>
   * If set to <code>true</code>, the execution is skipped.
   * <p>
   * A skipped excecution is signaled via the maven-property
   * <code>${hibernate.export.skipped}</code>.
   * <p>
   * The excecution is skipped automatically, if no modified or newly added
   * annotated classes are found and the dialect was not changed.
   *
   * @parameter property="maven.test.skip" default-value="false"
   */
  private boolean skip;

  /**
   * Force execution
   * <p>
   * Force execution, even if no modified or newly added annotated classes
   * where found and the dialect was not changed.
   * <p>
   * <code>skip</code> takes precedence over <code>force</code>.
   *
   * @parameter property="hibernate.export.force" default-value="false"
   */
  private boolean force;

  /**
   * SQL-Driver name.
   *
   * @parameter property="hibernate.connection.driver_class"
   */
  private String driverClassName;

  /**
   * Database URL.
   *
   * @parameter property="hibernate.connection.url"
   */
  private String url;

  /**
   * Database username
   *
   * @parameter property="hibernate.connection.username"
   */
  private String username;

  /**
   * Database password
   *
   * @parameter property="hibernate.connection.password"
   */
  private String password;

  /**
   * Hibernate dialect.
   *
   * @parameter property="hibernate.dialect"
   */
  private String hibernateDialect;

  /**
   * Path to Hibernate configuration file.
   *
   * @parameter default-value="${project.build.outputDirectory}/hibernate.properties"
   */
  private String hibernateProperties;

  /**
   * Target of execution:
   * <ul>
   *   <li><strong>NONE</strong> do nothing - just validate the configuration (forces excecution, signals skip)</li>
   *   <li><strong>EXPORT</strong> create database (<strong>DEFAULT!</strong>. forces excecution, signals skip)</li>
   *   <li><strong>SCRIPT</strong> export schema to SQL-script</li>
   *   <li><strong>BOTH</strong></li>
   * </ul>
   * @parameter property="hibernate.export.target" default-value="EXPORT"
   */
  private String target;

  /**
   * Type of execution.
   * <ul>
   *   <li><strong>NONE</strong> do nothing - just validate the configuration</li>
   *   <li><strong>CREATE</strong> create database-schema</li>
   *   <li><strong>DROP</strong> drop database-schema</li>
   *   <li><strong>BOTH</strong> (<strong>DEFAULT!</strong>)</li>
   * </ul>
   * @parameter property="hibernate.export.type" default-value="BOTH"
   */
  private String type;

  /**
   * Output file.
   *
   * @parameter property="hibernate.export.schema.filename" default-value="${project.build.directory}/schema.sql"
   */
  private String outputFile;

  /**
   * Delimiter in output-file.
   *
   * @parameter property="hibernate.export.schema.delimiter" default-value=";"
   */
  private String delimiter;

  /**
   * Format output-file.
   *
   * @parameter property="hibernate.export.schema.format" default-value="true"
   */
  private boolean format;


  @Override
  public void execute()
    throws
      MojoFailureException,
      MojoExecutionException
  {
    if (skip)
    {
      getLog().info("Exectuion of hibernate4-maven-plugin:export was skipped!");
      project.getProperties().setProperty(EXPORT_SKIPPED_PROPERTY, "true");
      return;
    }

    File dir = new File(outputDirectory);
    if (!dir.exists())
      throw new MojoExecutionException("Cannot scan for annotated classes in " + outputDirectory + ": directory does not exist!");

    Map<String,String> md5s;
    boolean modified = false;
    final File saved = new File(buildDirectory + File.separator + MD5S);

    if (saved.exists())
    {
      try
      {
        final FileInputStream fis = new FileInputStream(saved);
        final ObjectInputStream ois = new ObjectInputStream(fis);
        md5s = (HashMap<String,String>)ois.readObject();
        ois.close();
      }
      catch (final Exception e)
      {
        md5s = new HashMap<String,String>();
        getLog().warn("Cannot read timestamps from saved: " + e);
      }
    }
    else
    {
      md5s = new HashMap<String,String>();
      try
      {
        saved.createNewFile();
      }
      catch (final IOException e)
      {
        getLog().warn("Cannot create saved for timestamps: " + e);
      }
    }

    ClassLoader classLoader = null;
    try
    {
      getLog().debug("Creating ClassLoader for project-dependencies...");
      final List<String> classpathFiles = project.getCompileClasspathElements();
      if (scanTestClasses)
        classpathFiles.addAll(project.getTestClasspathElements());
      final URL[] urls = new URL[classpathFiles.size()];
      for (int i = 0; i < classpathFiles.size(); ++i)
      {
        getLog().debug("Dependency: " + classpathFiles.get(i));
        urls[i] = new File(classpathFiles.get(i)).toURI().toURL();
      }
      classLoader = new URLClassLoader(urls, getClass().getClassLoader());
    }
    catch (final Exception e)
    {
      getLog().error("Error while creating ClassLoader!", e);
      throw new MojoExecutionException(e.getMessage());
    }

    final Set<Class<?>> classes =
        new TreeSet<Class<?>>(
            new Comparator<Class<?>>() {
              @Override
              public int compare(final Class<?> a, final Class<?> b)
              {
                return a.getName().compareTo(b.getName());
              }
            }
          );

    try
    {
      final AnnotationDB db = new AnnotationDB();
      getLog().info("Scanning directory " + outputDirectory + " for annotated classes...");
      URL dirUrl = dir.toURI().toURL();
      db.scanArchives(dirUrl);
      if (scanTestClasses)
      {
        dir = new File(testOutputDirectory);
        if (!dir.exists())
          throw new MojoExecutionException("Cannot scan for annotated test-classes in " + testOutputDirectory + ": directory does not exist!");
        getLog().info("Scanning directory " + testOutputDirectory + " for annotated classes...");
        dirUrl = dir.toURI().toURL();
        db.scanArchives(dirUrl);
      }

      final Set<String> classNames = new HashSet<String>();
      if (db.getAnnotationIndex().containsKey(Entity.class.getName()))
        classNames.addAll(db.getAnnotationIndex().get(Entity.class.getName()));
      if (db.getAnnotationIndex().containsKey(MappedSuperclass.class.getName()))
        classNames.addAll(db.getAnnotationIndex().get(MappedSuperclass.class.getName()));
      if (db.getAnnotationIndex().containsKey(Embeddable.class.getName()))
        classNames.addAll(db.getAnnotationIndex().get(Embeddable.class.getName()));

      final MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
      for (final String name : classNames)
      {
        final Class<?> annotatedClass = classLoader.loadClass(name);
        classes.add(annotatedClass);
        final InputStream is =
            annotatedClass
                .getResourceAsStream(annotatedClass.getSimpleName() + ".class");
        final byte[] buffer = new byte[1024*4]; // copy data in 4MB-chunks
        int i;
        while((i = is.read(buffer)) > -1)
          digest.update(buffer, 0, i);
        is.close();
        final byte[] bytes = digest.digest();
        final BigInteger bi = new BigInteger(1, bytes);
        final String newMd5 = String.format("%0" + (bytes.length << 1) + "x", bi);
        final String oldMd5 = !md5s.containsKey(name) ? "" : md5s.get(name);
        if (!newMd5.equals(oldMd5))
        {
          getLog().debug("Found new or modified annotated class: " + name);
          modified = true;
          md5s.put(name, newMd5);
        }
        else
        {
          getLog().debug(oldMd5 + " -> class unchanged: " + name);
        }
      }
    }
    catch (final ClassNotFoundException e)
    {
      getLog().error("Error while adding annotated classes!", e);
      throw new MojoExecutionException(e.getMessage());
    }
    catch (final Exception e)
    {
      getLog().error("Error while scanning!", e);
      throw new MojoFailureException(e.getMessage());
    }

    if (classes.isEmpty())
      throw new MojoFailureException("No annotated classes found in directory " + outputDirectory);

    getLog().debug("Detected classes with mapping-annotations:");
    for (final Class<?> annotatedClass : classes)
      getLog().debug("  " + annotatedClass.getName());


    final Properties properties = new Properties();

    /** Try to read configuration from properties-file */
    try
    {
      final File file = new File(hibernateProperties);
      if (file.exists())
      {
        getLog().info("Reading properties from file " + hibernateProperties + "...");
        properties.load(new FileInputStream(file));
      }
      else
        getLog().info("No hibernate-properties-file found! (Checked path: " + hibernateProperties + ")");
    }
    catch (final IOException e)
    {
      getLog().error("Error while reading properties!", e);
      throw new MojoExecutionException(e.getMessage());
    }

    /** Overwrite values from propertie-file or set, if given */
    if (driverClassName != null)
    {
      if (properties.containsKey(DRIVER_CLASS))
        getLog().debug(
            "Overwriting property " +
            DRIVER_CLASS + "=" + properties.getProperty(DRIVER_CLASS) +
            " with the value " + driverClassName
          );
      else
        getLog().debug("Using the value " + driverClassName);
      properties.setProperty(DRIVER_CLASS, driverClassName);
    }
    if (url != null)
    {
      if (properties.containsKey(URL))
        getLog().debug(
            "Overwriting property " +
            URL + "=" + properties.getProperty(URL) +
            " with the value " + url
          );
      else
        getLog().debug("Using the value " + url);
      properties.setProperty(URL, url);
    }
    if (username != null)
    {
      if (properties.containsKey(USERNAME))
        getLog().debug(
            "Overwriting property " +
            USERNAME + "=" + properties.getProperty(USERNAME) +
            " with the value " + username
          );
      else
        getLog().debug("Using the value " + username);
      properties.setProperty(USERNAME, username);
    }
    if (password != null)
    {
      if (properties.containsKey(PASSWORD))
        getLog().debug(
            "Overwriting property " +
            PASSWORD + "=" + properties.getProperty(PASSWORD) +
            " with the value " + password
          );
      else
        getLog().debug("Using the value " + password);
      properties.setProperty(PASSWORD, password);
    }
    if (hibernateDialect != null)
    {
      if (properties.containsKey(DIALECT))
        getLog().debug(
            "Overwriting property " +
            DIALECT + "=" + properties.getProperty(DIALECT) +
            " with the value " + hibernateDialect
          );
      else
        getLog().debug("Using the value " + hibernateDialect);
      properties.setProperty(DIALECT, hibernateDialect);
    }

    /** The generated SQL varies with the dialect! */
    if (md5s.containsKey(DIALECT))
    {
      final String dialect = properties.getProperty(DIALECT);
      if (md5s.get(DIALECT).equals(dialect))
        getLog().debug("SQL-dialect unchanged.");
      else
      {
        getLog().debug("SQL-dialect changed: " + dialect);
        modified = true;
        md5s.put(DIALECT, dialect);
      }
    }
    else
    {
      modified = true;
      md5s.put(DIALECT, properties.getProperty(DIALECT));
    }

    if (properties.isEmpty())
    {
      getLog().error("No properties set!");
      throw new MojoFailureException("Hibernate-Configuration is missing!");
    }

    final Configuration config = new Configuration();
    config.setProperties(properties);

    // [nicus] Explicitly set NamingStrategy
    if ( properties.containsKey(NAMING_STRATEGY)) {
    	final String namingStrategy = properties.getProperty(NAMING_STRATEGY);
    	getLog().debug("Explicitly set NamingStrategy: " + namingStrategy);
    	try {
    		@SuppressWarnings("unchecked")
			final Class<NamingStrategy> namingStrategyClass = (Class<NamingStrategy>) Class.forName(namingStrategy);
			config.setNamingStrategy(  namingStrategyClass.newInstance() );
		} catch (final Exception e) {
		      getLog().error("Error setting NamingStrategy", e);
		      throw new MojoExecutionException(e.getMessage());
		}
    }

    getLog().debug("Adding annotated classes to hibernate-mapping-configuration...");
    for (final Class<?> annotatedClass : classes)
    {
      getLog().debug("Class " + annotatedClass);
      config.addAnnotatedClass(annotatedClass);
    }

    Target target = null;
    try
    {
      target = Target.valueOf(this.target.toUpperCase());
    }
    catch (final IllegalArgumentException e)
    {
      getLog().error("Invalid value for configuration-option \"target\": " + this.target);
      getLog().error("Valid values are: NONE, SCRIPT, EXPORT, BOTH");
      throw new MojoExecutionException("Invalid value for configuration-option \"target\"");
    }
    Type type = null;
    try
    {
      type = Type.valueOf(this.type.toUpperCase());
    }
    catch (final IllegalArgumentException e)
    {
      getLog().error("Invalid value for configuration-option \"type\": " + this.type);
      getLog().error("Valid values are: NONE, CREATE, DROP, BOTH");
      throw new MojoExecutionException("Invalid value for configuration-option \"type\"");
    }

    if (target.equals(Target.SCRIPT) || target.equals(Target.NONE))
    {
      project.getProperties().setProperty(EXPORT_SKIPPED_PROPERTY, "true");
    }
    if (
        !modified
        && !target.equals(Target.SCRIPT)
        && !target.equals(Target.NONE)
        && !force
      )
    {
      getLog().info("No modified annotated classes found and dialect unchanged.");
      getLog().info("Skipping schema generation!");
      project.getProperties().setProperty(EXPORT_SKIPPED_PROPERTY, "true");
      return;
    }

    getLog().info("Gathered hibernate-configuration (turn on debugging for details):");
    for (final Entry<Object,Object> entry : properties.entrySet())
      getLog().info("  " + entry.getKey() + " = " + entry.getValue());

    Connection connection = null;
    try
    {
      /**
       * The connection must be established outside of hibernate, because
       * hibernate does not use the context-classloader of the current
       * thread and, hence, would not be able to resolve the driver-class!
       */
      switch (target)
      {
        case EXPORT:
        case BOTH:
          switch (type)
          {
            case CREATE:
            case DROP:
            case BOTH:
              final Class driverClass = classLoader.loadClass(properties.getProperty(DRIVER_CLASS));
              getLog().debug("Registering JDBC-driver " + driverClass.getName());
              DriverManager.registerDriver(new DriverProxy((Driver)driverClass.newInstance()));
              getLog().debug(
                  "Opening JDBC-connection to "
                  + properties.getProperty(URL)
                  + " as "
                  + properties.getProperty(USERNAME)
                  + " with password "
                  + properties.getProperty(PASSWORD)
                  );
              connection = DriverManager.getConnection(
                  properties.getProperty(URL),
                  properties.getProperty(USERNAME),
                  properties.getProperty(PASSWORD)
                  );
          }
      }
    }
    catch (final ClassNotFoundException e)
    {
      getLog().error("Dependency for driver-class " + properties.getProperty(DRIVER_CLASS) + " is missing!");
      throw new MojoExecutionException(e.getMessage());
    }
    catch (final Exception e)
    {
      getLog().error("Cannot establish connection to database!");
      final Enumeration<Driver> drivers = DriverManager.getDrivers();
      if (!drivers.hasMoreElements())
        getLog().error("No drivers registered!");
      while (drivers.hasMoreElements())
        getLog().debug("Driver: " + drivers.nextElement());
      throw new MojoExecutionException(e.getMessage());
    }

    final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    MavenLogAppender.startPluginLog(this);
    try
    {
      /**
       * Change class-loader of current thread, so that hibernate can
       * see all dependencies!
       */
      Thread.currentThread().setContextClassLoader(classLoader);

      final SchemaExport export = new SchemaExport(config, connection);
      export.setOutputFile(outputFile);
      export.setDelimiter(delimiter);
      export.setFormat(format);
      export.execute(target, type);

      for (final Object exception : export.getExceptions())
        getLog().debug(exception.toString());
    }
    finally
    {
      /** Stop Log-Capturing */
      MavenLogAppender.endPluginLog(this);

      /** Restore the old class-loader (TODO: is this really necessary?) */
      Thread.currentThread().setContextClassLoader(contextClassLoader);

      /** Close the connection */
      try
      {
        if (connection != null)
          connection.close();
      }
      catch (final SQLException e)
      {
        getLog().error("Error while closing connection: " + e.getMessage());
      }
    }

    /** Write md5-sums for annotated classes to file */
    try
    {
      final FileOutputStream fos = new FileOutputStream(saved);
      final ObjectOutputStream oos = new ObjectOutputStream(fos);
      oos.writeObject(md5s);
      oos.close();
      fos.close();
    }
    catch (final Exception e)
    {
      getLog().error("Cannot write md5-sums to file: " + e);
    }
  }

  /**
   * Needed, because DriverManager won't pick up drivers, that were not
   * loaded by the system-classloader!
   * See:
   * http://stackoverflow.com/questions/288828/how-to-use-a-jdbc-driver-fromodifiedm-an-arbitrary-location
   */
  static final class DriverProxy implements Driver
  {
    private final Driver target;

    DriverProxy(final Driver target)
    {
      if (target == null)
        throw new NullPointerException();
      this.target = target;
    }

    public java.sql.Driver getTarget()
    {
      return target;
    }

    @Override
    public boolean acceptsURL(final String url) throws SQLException
    {
      return target.acceptsURL(url);
    }

    @Override
    public java.sql.Connection connect(
        final String url,
        final java.util.Properties info
      )
      throws
        SQLException
    {
      return target.connect(url, info);
    }

    @Override
    public int getMajorVersion()
    {
      return target.getMajorVersion();
    }

    @Override
    public int getMinorVersion()
    {
      return target.getMinorVersion();
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(
        final String url,
        final Properties info
      )
      throws
        SQLException
    {
      return target.getPropertyInfo(url, info);
    }

    @Override
    public boolean jdbcCompliant()
    {
      return target.jdbcCompliant();
    }

    /**
     * This Method cannot be annotated with @Override, becaus the plugin
     * will not compile then under Java 1.6!
     */
    public Logger getParentLogger() throws SQLFeatureNotSupportedException
    {
      throw new SQLFeatureNotSupportedException("Not supported, for backward-compatibility with Java 1.6");
    }

    @Override
    public String toString()
    {
      return "Proxy: " + target;
    }

    @Override
    public int hashCode()
    {
      return target.hashCode();
    }

    @Override
    public boolean equals(final Object obj)
    {
      if (!(obj instanceof DriverProxy))
        return false;
      final DriverProxy other = (DriverProxy) obj;
      return this.target.equals(other.target);
    }
  }
}
