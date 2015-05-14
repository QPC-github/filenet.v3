// Copyright 2015 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.filenet4;

import com.google.common.base.Strings;
import com.google.enterprise.connector.filenet4.filewrap.IBaseObject;
import com.google.enterprise.connector.filenet4.filewrap.IDocument;
import com.google.enterprise.connector.filenet4.filewrap.IFolder;
import com.google.enterprise.connector.filenet4.filewrap.IId;
import com.google.enterprise.connector.filenet4.filewrap.IObjectFactory;
import com.google.enterprise.connector.filenet4.filewrap.IObjectSet;
import com.google.enterprise.connector.filenet4.filewrap.IObjectStore;
import com.google.enterprise.connector.filenet4.filewrap.ISearch;
import com.google.enterprise.connector.spi.Document;
import com.google.enterprise.connector.spi.DocumentList;
import com.google.enterprise.connector.spi.RepositoryException;
import com.google.enterprise.connector.spi.SpiConstants.AclInheritanceType;
import com.google.enterprise.connector.spi.TraversalContext;
import com.google.enterprise.connector.spi.Value;

import com.filenet.api.constants.ClassNames;
import com.filenet.api.constants.GuidConstants;
import com.filenet.api.constants.PermissionSource;
import com.filenet.api.constants.PropertyNames;

import java.text.MessageFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FolderTraverser implements DocumentList, Traverser {
  private static final Logger LOGGER =
      Logger.getLogger(FolderTraverser.class.getName());

  private static final String FOLDER_QUERY_WITH_UUID =
      "SELECT {0}* FROM '" + GuidConstants.Class_Folder
      + "' WHERE ((" + PropertyNames.DATE_LAST_MODIFIED + " = {1}) AND ("
      + PropertyNames.ID + " > {2})) OR ("
      + PropertyNames.DATE_LAST_MODIFIED + " > {1}) ORDER BY "
      + PropertyNames.DATE_LAST_MODIFIED + "," + PropertyNames.ID;

  private static final String FOLDER_QUERY_WITHOUT_UUID =
      "SELECT {0}* FROM '" + GuidConstants.Class_Folder
      + "' WHERE " + PropertyNames.DATE_LAST_MODIFIED + " > {1} ORDER BY "
      + PropertyNames.DATE_LAST_MODIFIED + "," + PropertyNames.ID;

  private final IObjectFactory objectFactory;
  private final IObjectStore os;
  private final FileConnector connector;

  private TraversalContext traversalContext;
  private int batchHint = 500;
  private String checkpointTime;
  private String checkpointUuid;

  private Date folderLastModified;
  private IId folderLastUuid;
  private LinkedList<AclDocument> acls;
  private Checkpoint checkpoint;

  public FolderTraverser(IObjectFactory objectFactory, IObjectStore os,
      FileConnector connector) {
    this.objectFactory = objectFactory;
    this.os = os;
    this.connector = connector;
  }

  @Override
  public void setBatchHint(int batchHint) throws RepositoryException {
    this.batchHint = batchHint;
    LOGGER.finest("Setting batchHint for FolderTraverser to " + batchHint);
  }

  @Override
  public DocumentList getDocumentList(Checkpoint checkpoint)
      throws RepositoryException {
    LOGGER.info("Searching for documents in updated folders");
    os.refreshSUserContext();
    this.checkpoint = checkpoint;
    checkpointTime = getLastModified();
    acls = searchDocs();
    if (acls.size() > 0) {
      return this;
    } else {
      return null;
    }
  }

  private String getLastModified() {
    String lastModified =
        getCheckpointValue(Checkpoint.JsonField.LAST_FOLDER_TIME);
    if (lastModified == null) {
      lastModified =
          getCheckpointValue(Checkpoint.JsonField.LAST_MODIFIED_TIME);
      if (lastModified == null) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        lastModified = Value.calendarToIso8601(cal);
        LOGGER.log(Level.FINEST, "Checkpoint does not contain folder or "
            + "document last modified time, use current time [{0}] to search"
            + " for folder updates", lastModified);
      } else {
        LOGGER.log(Level.FINEST, "Checkpoint does not contain folder last "
            + "modified time, use document last modified time [{0}] to search"
            + " for folder updates", lastModified);
      }
    }
    return lastModified;
  }

  private String getCheckpointValue(Checkpoint.JsonField field) {
    try {
      return checkpoint.getString(field);
    } catch (RepositoryException e) {
      LOGGER.log(Level.FINEST,
          "Failed to lookup " + field + " field in checkpoint");
      return null;
    }
  }

  private LinkedList<AclDocument> searchDocs() throws RepositoryException {
    ISearch searcher = objectFactory.getSearch(os);
    LinkedList<AclDocument> aclDocs = new LinkedList<AclDocument>();
    IObjectSet folderSet = searcher.execute(getQuery(), 100, 0,
        objectFactory.getFactory(ClassNames.FOLDER));
    Iterator<? extends IBaseObject> folderIter = folderSet.getIterator();
    while (folderIter.hasNext()) {
      IFolder folder = (IFolder) folderIter.next();
      IObjectSet docSet = folder.get_ContainedDocuments();
      LOGGER.log(Level.FINEST, "Found {0} documents under {1} folder [{2}]",
          new Object[] {docSet.getSize(), folder.get_FolderName(),
              folder.get_Id()});
      Iterator<? extends IBaseObject> docIter = docSet.getIterator();
      while (docIter.hasNext()) {
        IDocument doc = (IDocument) docIter.next();
        Permissions permissions = new Permissions(doc.get_Permissions());
        AclDocument aclDoc = new AclDocument(
            doc.get_Id().toString() + AclDocument.SEC_FOLDER_POSTFIX, null,
            AclInheritanceType.CHILD_OVERRIDES,
            connector.getGoogleGlobalNamespace(),
            permissions.getAllowUsers(PermissionSource.SOURCE_PARENT),
            permissions.getDenyUsers(PermissionSource.SOURCE_PARENT),
            permissions.getAllowGroups(PermissionSource.SOURCE_PARENT),
            permissions.getDenyGroups(PermissionSource.SOURCE_PARENT));
        aclDoc.setCheckpointLastModified(folder.getModifyDate());
        aclDoc.setCheckpointLastUuid(folder.get_Id());
        aclDocs.add(aclDoc);
      }
    }
    return aclDocs;
  }

  private String getQuery() {
    String timeStr = FileUtil.getQueryTimeString(checkpointTime);
    checkpointUuid = getCheckpointValue(Checkpoint.JsonField.UUID_FOLDER);

    String topClause =
        (batchHint > 0) ? " TOP " + String.valueOf(batchHint) : "";
    if (Strings.isNullOrEmpty(checkpointUuid)) {
      return MessageFormat.format(FOLDER_QUERY_WITHOUT_UUID,
          new Object[] {topClause, timeStr});
    } else {
      return MessageFormat.format(FOLDER_QUERY_WITH_UUID,
          new Object[] {topClause, timeStr, checkpointUuid});
    }
  }

  @Override
  public Document nextDocument() throws RepositoryException {
    if (acls == null || acls.isEmpty()) {
      return null;
    } else {
      AclDocument aclDoc = acls.poll();
      folderLastUuid = aclDoc.getCheckpointLastUuid();
      folderLastModified = aclDoc.getCheckpointLastModified();
      return aclDoc;
    }
  }

  @Override
  public String checkpoint() throws RepositoryException {
    checkpoint.setTimeAndUuid(
        Checkpoint.JsonField.LAST_FOLDER_TIME, folderLastModified,
        Checkpoint.JsonField.UUID_FOLDER, folderLastUuid);
    LOGGER.log(Level.FINEST, "Saving folder's last modified time [{0}] and "
        + "UUID [{1}] to the checkpoint",
        new Object[] {folderLastModified, folderLastUuid});
    return checkpoint.toString();
  }

  @Override
  public void setTraversalContext(TraversalContext traversalContext) {
    this.traversalContext = traversalContext;
  }
}
