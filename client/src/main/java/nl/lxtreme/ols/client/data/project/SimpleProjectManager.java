/*
 * OpenBench LogicSniffer / SUMP project 
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or (at
 * your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin St, Fifth Floor, Boston, MA 02110, USA
 *
 * 
 * Copyright (C) 2010-2011 - J.W. Janssen, http://www.lxtreme.nl
 */
package nl.lxtreme.ols.client.data.project;


import java.beans.*;
import java.io.*;
import java.util.*;
import java.util.logging.*;
import java.util.zip.*;

import nl.lxtreme.ols.api.*;
import nl.lxtreme.ols.api.acquisition.*;
import nl.lxtreme.ols.api.data.*;
import nl.lxtreme.ols.api.data.project.*;
import nl.lxtreme.ols.client.data.settings.*;
import nl.lxtreme.ols.util.*;


/**
 * Provides a simple implementation of a project manager, which writes an entire
 * project as (compressed) ZIP-file.
 */
public class SimpleProjectManager implements ProjectManager, ProjectProperties
{
  // CONSTANTS

  private static final String FILENAME_PROJECT_METADATA = "ols.project";
  private static final String FILENAME_CHANNEL_LABELS = "channel.labels";
  private static final String FILENAME_PROJECT_SETTINGS = "settings/";
  private static final String FILENAME_CAPTURE_RESULTS = "data.ols";

  private static final Logger LOG = Logger.getLogger( SimpleProjectManager.class.getName() );

  // VARIABLES

  private volatile HostProperties hostProperties;
  private ProjectImpl project;

  // CONSTRUCTORS

  /**
   * Creates a new SimpleProjectManager instance.
   */
  public SimpleProjectManager()
  {
    this.project = new ProjectImpl();
  }

  // METHODS

  /**
   * @see nl.lxtreme.ols.api.data.project.ProjectManager#addPropertyChangeListener(java.beans.PropertyChangeListener)
   */
  public void addPropertyChangeListener( final PropertyChangeListener aListener )
  {
    this.project.addPropertyChangeListener( aListener );
  }

  /**
   * @see nl.lxtreme.ols.api.data.project.ProjectManager#createNewProject()
   */
  public Project createNewProject()
  {
    return setProject( new ProjectImpl() );
  }

  /**
   * @see nl.lxtreme.ols.api.data.project.ProjectManager#createTemporaryProject()
   */
  public Project createTemporaryProject()
  {
    return new ProjectImpl();
  }

  /**
   * @see nl.lxtreme.ols.api.data.project.ProjectManager#getCurrentProject()
   */
  @Override
  public Project getCurrentProject()
  {
    return this.project;
  }

  /**
   * @see nl.lxtreme.ols.api.data.project.ProjectManager#loadProject(java.io.InputStream)
   */
  @Override
  public void loadProject( final InputStream aInput ) throws IOException
  {
    if ( aInput == null )
    {
      throw new IllegalArgumentException( "Input stream cannot be null!" );
    }

    final BufferedInputStream in = new BufferedInputStream( aInput );
    final ZipInputStream zipIS = new ZipInputStream( in );

    final ProjectImpl newProject = new ProjectImpl();

    try
    {
      ZipEntry ze = null;
      boolean entriesSeen = false;
      while ( ( ze = zipIS.getNextEntry() ) != null )
      {

        final String name = ze.getName();
        if ( FILENAME_PROJECT_METADATA.equals( name ) )
        {
          loadProjectMetadata( newProject, zipIS );
          entriesSeen = true;
        }
        else if ( FILENAME_CHANNEL_LABELS.equals( name ) )
        {
          loadChannelLabels( newProject, zipIS );
          entriesSeen = true;
        }
        else if ( FILENAME_CAPTURE_RESULTS.equals( name ) )
        {
          loadCapturedResults( newProject, zipIS );
          entriesSeen = true;
        }
        else if ( name.startsWith( FILENAME_PROJECT_SETTINGS ) )
        {
          final String userSettingsName = name.substring( FILENAME_PROJECT_SETTINGS.length() );
          loadProjectSettings( newProject, userSettingsName, zipIS );
          entriesSeen = true;
        }

        zipIS.closeEntry();
      }

      if ( !entriesSeen )
      {
        throw new IOException( "Invalid project file!" );
      }

      // Publish the newly loaded project...
      setProject( newProject );

      // Mark the project as no longer changed...
      newProject.setChanged( false );
    }
    finally
    {
      HostUtils.closeResource( zipIS );
    }
  }

  /**
   * @see nl.lxtreme.ols.api.data.project.ProjectManager#removePropertyChangeListener(java.beans.PropertyChangeListener)
   */
  public void removePropertyChangeListener( final PropertyChangeListener aListener )
  {
    this.project.removePropertyChangeListener( aListener );
  }

  /**
   * @see nl.lxtreme.ols.api.data.project.ProjectManager#saveProject(java.io.OutputStream)
   */
  @Override
  public void saveProject( final OutputStream aOutput ) throws IOException
  {
    if ( aOutput == null )
    {
      throw new IllegalArgumentException( "Output stream cannot be null!" );
    }

    final BufferedOutputStream os = new BufferedOutputStream( aOutput );
    final ZipOutputStream zipOS = new ZipOutputStream( os );

    zipOS.setComment( this.hostProperties.getFullName().concat( " project file" ) );

    try
    {
      storeProjectMetadata( this.project, zipOS );
      // Store the channel labels...
      storeChannelLabels( this.project, zipOS );
      // Store the settings...
      storeProjectSettings( this.project, zipOS );
      // Store the last capture results...
      storeCapturedResults( this.project, zipOS );

      // Mark the project as no longer changed...
      this.project.setChanged( false );
    }
    finally
    {
      HostUtils.closeResource( zipOS );
      HostUtils.closeResource( os );
    }
  }

  /**
   * Sets hostProperties to the given value.
   * 
   * @param aHostProperties
   *          the hostProperties to set.
   */
  public void setHostProperties( final HostProperties aHostProperties )
  {
    this.hostProperties = aHostProperties;
  }

  /**
   * Reads the capture results from the given ZIP-input stream.
   * 
   * @param aProject
   *          the project to read the capture results for;
   * @param aZipIS
   *          the ZIP input stream to read the capture results from.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void loadCapturedResults( final Project aProject, final ZipInputStream aZipIS ) throws IOException
  {
    OlsDataHelper.read( aProject, new InputStreamReader( aZipIS ) );
  }

  /**
   * Reads the project channel labels from the given ZIP-input stream.
   * 
   * @param aProject
   *          the project to read the channel labels for;
   * @param aZipIS
   *          the ZIP input stream to read the channel labels from.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void loadChannelLabels( final Project aProject, final ZipInputStream aZipIS ) throws IOException
  {
    final InputStreamReader isReader = new InputStreamReader( aZipIS );
    final BufferedReader reader = new BufferedReader( isReader );

    String[] labels = new String[Ols.MAX_CHANNELS];

    try
    {
      String label = null;
      int idx = 0;
      while ( ( ( label = reader.readLine() ) != null ) && ( idx < labels.length ) )
      {
        labels[idx++] = StringUtils.isEmpty( label ) ? null : label;
      }
    }
    finally
    {
      aProject.setChannelLabels( labels );
    }
  }

  /**
   * Reads the project metadata to the given ZIP-input stream.
   * 
   * @param aProject
   *          the project to read the metadata for;
   * @param aZipIS
   *          the ZIP input stream to read the metadata from.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void loadProjectMetadata( final Project aProject, final ZipInputStream aZipIS ) throws IOException
  {
    final InputStreamReader isReader = new InputStreamReader( aZipIS );
    final BufferedReader reader = new BufferedReader( isReader );

    String name = null;
    String version = null;
    Date savedAt = null;

    try
    {
      name = reader.readLine();
      version = reader.readLine();
      savedAt = new Date( Long.valueOf( reader.readLine() ).longValue() );

      LOG.log( Level.INFO, "Reading project {0}, created at {1} by OLSv{2}.", new Object[] { name, savedAt, version } );
    }
    finally
    {
      aProject.setName( name );
      aProject.setSourceVersion( version );
      aProject.setLastModified( savedAt );
    }
  }

  /**
   * Reads the project settings to the given ZIP-input stream.
   * 
   * @param aProject
   *          the project to read the settings for;
   * @param aUserSettingsName
   *          the name of the user settings that is to be loaded;
   * @param aZipIS
   *          the ZIP input stream to read the settings from.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void loadProjectSettings( final ProjectImpl aProject, final String aUserSettingsName,
      final ZipInputStream aZipIS ) throws IOException
  {
    final Properties settings = new Properties();
    try
    {
      settings.load( aZipIS );
    }
    finally
    {
      final UserSettingsImpl userSettings = new UserSettingsImpl( aUserSettingsName, settings );
      aProject.setSettings( userSettings );
    }
  }

  /**
   * Stores the captured results to the given ZIP-output stream.
   * <p>
   * If the given project does not have capture results, this method does
   * nothing.
   * </p>
   * 
   * @param aProject
   *          the project to write the capture results for;
   * @param aZipOS
   *          the ZIP output stream to write the capture results to.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void storeCapturedResults( final Project aProject, final ZipOutputStream aZipOS ) throws IOException
  {
    final AcquisitionResult data = aProject.getCapturedData();
    if ( data == null )
    {
      return;
    }

    final ZipEntry zipEntry = new ZipEntry( FILENAME_CAPTURE_RESULTS );
    aZipOS.putNextEntry( zipEntry );

    OlsDataHelper.write( aProject, new OutputStreamWriter( aZipOS ) );
  }

  /**
   * Stores the channel labels to the given ZIP-output stream.
   * <p>
   * If the given project does not have channel labels, this method does
   * nothing.
   * </p>
   * 
   * @param aProject
   *          the project to write the channel labels for;
   * @param aZipOS
   *          the ZIP output stream to write the channel labels to.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void storeChannelLabels( final Project aProject, final ZipOutputStream aZipOS ) throws IOException
  {
    final String[] labels = aProject.getChannelLabels();
    if ( ( labels == null ) || ( labels.length == 0 ) )
    {
      return;
    }

    final ZipEntry zipEntry = new ZipEntry( FILENAME_CHANNEL_LABELS );
    aZipOS.putNextEntry( zipEntry );

    // Write the channel labels
    PrintStream out = new PrintStream( aZipOS );

    try
    {
      for ( String label : labels )
      {
        out.println( StringUtils.isEmpty( label ) ? "" : label );
      }
    }
    finally
    {
      out.flush();
      out = null;
    }
  }

  /**
   * Stores the project metadata to the given ZIP-output stream.
   * <p>
   * In case the given project does not have a project name, this method does
   * nothing.
   * </p>
   * 
   * @param aProject
   *          the project to write the metadata for;
   * @param aZipOS
   *          the ZIP output stream to write the metadata to.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void storeProjectMetadata( final Project aProject, final ZipOutputStream aZipOS ) throws IOException
  {
    final String name = aProject.getName();
    if ( ( name == null ) || name.trim().isEmpty() )
    {
      return;
    }

    final ZipEntry zipEntry = new ZipEntry( FILENAME_PROJECT_METADATA );
    aZipOS.putNextEntry( zipEntry );

    // Write the project metadata...
    PrintStream out = new PrintStream( aZipOS );

    try
    {
      out.println( name );
      out.println( this.hostProperties.getVersion() );
      out.println( System.currentTimeMillis() );
    }
    finally
    {
      out.flush();
      out = null;
    }
  }

  /**
   * Stores the project settings to the given ZIP-output stream.
   * <p>
   * In case the given project does not have project settings, this method does
   * nothing.
   * </p>
   * 
   * @param aProject
   *          the project to write the settings for;
   * @param aZipOS
   *          the ZIP output stream to write the settings to.
   * @throws IOException
   *           in case of I/O problems.
   */
  protected void storeProjectSettings( final ProjectImpl aProject, final ZipOutputStream aZipOS ) throws IOException
  {
    try
    {
      aProject.visit( new ProjectVisitor()
      {
        @Override
        public void visit( final UserSettings aSettings ) throws IOException
        {
          final String zipEntryName = FILENAME_PROJECT_SETTINGS.concat( aSettings.getName() );

          final ZipEntry zipEntry = new ZipEntry( zipEntryName );
          aZipOS.putNextEntry( zipEntry );

          // Convert to a properties object...
          final Properties props = new Properties();
          for ( Map.Entry<String, Object> userSetting : aSettings )
          {
            props.put( userSetting.getKey(), userSetting.getValue() );
          }

          // Write the project settings
          props.store( aZipOS, aSettings.getName().concat( " settings" ) );
        }
      } );
    }
    finally
    {
      aZipOS.flush();
    }
  }

  /**
   * Sets the current project to the given project, moving all registered
   * property change listeners to the new project.
   * 
   * @param aProject
   *          the project to set, cannot be <code>null</code>.
   */
  private ProjectImpl setProject( final ProjectImpl aProject )
  {
    final PropertyChangeListener[] listeners = this.project.getPropertyChangeListeners();
    for ( PropertyChangeListener listener : listeners )
    {
      this.project.removePropertyChangeListener( listener );
      aProject.addPropertyChangeListener( listener );
    }
    this.project = aProject;
    return aProject;
  }
}
