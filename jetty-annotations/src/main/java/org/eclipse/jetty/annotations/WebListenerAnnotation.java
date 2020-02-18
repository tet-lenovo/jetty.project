//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.annotations;

import java.util.EventListener;
import javax.servlet.ServletContextAttributeListener;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletRequestAttributeListener;
import javax.servlet.ServletRequestListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.servlet.ListenerHolder;
import org.eclipse.jetty.servlet.Source;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.DiscoveredAnnotation;
import org.eclipse.jetty.webapp.MetaData;
import org.eclipse.jetty.webapp.Origin;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * WebListenerAnnotation
 */
public class WebListenerAnnotation extends DiscoveredAnnotation
{
    private static final Logger LOG = Log.getLogger(WebListenerAnnotation.class);

    public WebListenerAnnotation(WebAppContext context, String className)
    {
        super(context, className);
    }

    public WebListenerAnnotation(WebAppContext context, String className, Resource resource)
    {
        super(context, className, resource);
    }

    @Override
    public void apply()
    {
        Class<? extends java.util.EventListener> clazz = (Class<? extends EventListener>)getTargetClass();

        if (clazz == null)
        {
            LOG.warn(_className + " cannot be loaded");
            return;
        }

        try
        {
            if (ServletContextListener.class.isAssignableFrom(clazz) ||
                ServletContextAttributeListener.class.isAssignableFrom(clazz) ||
                ServletRequestListener.class.isAssignableFrom(clazz) ||
                ServletRequestAttributeListener.class.isAssignableFrom(clazz) ||
                HttpSessionListener.class.isAssignableFrom(clazz) ||
                HttpSessionAttributeListener.class.isAssignableFrom(clazz) ||
                HttpSessionIdListener.class.isAssignableFrom(clazz))
            {
                MetaData metaData = _context.getMetaData();
                if (metaData.getOrigin(clazz.getName() + ".listener") == Origin.NotSet)
                {
                    ListenerHolder h = _context.getServletHandler().newListenerHolder(new Source(Source.Origin.ANNOTATION, clazz.getName()));
                    h.setHeldClass(clazz);
                    _context.getServletHandler().addListener(h);
                }
            }
            else
                LOG.warn(clazz.getName() + " does not implement one of the servlet listener interfaces");
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }
}
