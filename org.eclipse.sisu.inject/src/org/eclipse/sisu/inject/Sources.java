/*******************************************************************************
 * Copyright (c) 2010, 2015 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Stuart McCulloch (Sonatype, Inc.) - initial API and implementation
 *******************************************************************************/
package org.eclipse.sisu.inject;

import java.lang.annotation.Annotation;

import org.eclipse.sisu.Description;
import org.eclipse.sisu.Internal;
import org.eclipse.sisu.Priority;

import com.google.inject.Binding;

/**
 * Utility methods for dealing with annotated sources.
 */
public final class Sources
{
    // ----------------------------------------------------------------------
    // Static initialization
    // ----------------------------------------------------------------------

    static
    {
        boolean hasDeclaringSource;
        try
        {
            // support future where binding.getSource() returns ElementSource and not the original declaring source
            hasDeclaringSource = com.google.inject.spi.ElementSource.class.getMethod( "getDeclaringSource" ) != null;
        }
        catch ( final Exception e )
        {
            hasDeclaringSource = false;
        }
        catch ( final LinkageError e )
        {
            hasDeclaringSource = false;
        }
        HAS_DECLARING_SOURCE = hasDeclaringSource;
    }

    // ----------------------------------------------------------------------
    // Constants
    // ----------------------------------------------------------------------

    private static final boolean HAS_DECLARING_SOURCE;

    // ----------------------------------------------------------------------
    // Constructors
    // ----------------------------------------------------------------------

    private Sources()
    {
        // static utility class, not allowed to create instances
    }

    // ----------------------------------------------------------------------
    // Utility methods
    // ----------------------------------------------------------------------

    /**
     * Hides a new binding source from the bean locator.
     * 
     * @return Internal source
     */
    public static Internal hide()
    {
        return hide( null );
    }

    /**
     * Hides the given binding source from the bean locator.
     * 
     * @param source The source
     * @return Internal source
     */
    public static Internal hide( final Object source )
    {
        return new InternalSource( source );
    }

    /**
     * Describes a new binding source with the given description.
     * 
     * @param value The description
     * @return Described source
     */
    public static Description describe( final String value )
    {
        return describe( null, value );
    }

    /**
     * Describes the given binding source with the given description.
     * 
     * @param source The source
     * @param value The description
     * @return Described source
     */
    public static Description describe( final Object source, final String value )
    {
        return new DescriptionSource( source, value );
    }

    /**
     * Prioritizes a new binding source with the given priority.
     * 
     * @param value The priority
     * @return Prioritized source
     */
    public static Priority prioritize( final int value )
    {
        return prioritize( null, value );
    }

    /**
     * Prioritizes the given binding source with the given priority.
     * 
     * @param source The source
     * @param value The priority
     * @return Prioritized source
     */
    public static Priority prioritize( final Object source, final int value )
    {
        return new PrioritySource( source, value );
    }

    // ----------------------------------------------------------------------
    // Local methods
    // ----------------------------------------------------------------------

    /**
     * Returns the source that originally declared the given binding.
     * 
     * @param binding The binding
     * @return Declaring source; {@code null} if it doesn't exist
     */
    static Object getDeclaringSource( final Binding<?> binding )
    {
        final Object source = binding.getSource();
        if ( HAS_DECLARING_SOURCE && source instanceof com.google.inject.spi.ElementSource )
        {
            return ( (com.google.inject.spi.ElementSource) source ).getDeclaringSource();
        }
        return source;
    }

    /**
     * Searches the binding's source and implementation for an annotation of the given type.
     * 
     * @param binding The binding
     * @param annotationType The annotation type
     * @return Annotation instance; {@code null} if it doesn't exist
     */
    static <T extends Annotation> T getAnnotation( final Binding<?> binding, final Class<T> annotationType )
    {
        T annotation = null;
        final Object source = getDeclaringSource( binding );
        if ( source instanceof AnnotatedSource )
        {
            annotation = ( (AnnotatedSource) source ).getAnnotation( annotationType );
        }
        if ( null == annotation )
        {
            annotation = Implementations.getAnnotation( binding, annotationType );
        }
        return annotation;
    }
}
