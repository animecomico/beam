/*
 * Copyright (C) 2011 Brockmann Consult GmbH (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */

package org.esa.beam.dataio.netcdf.metadata.profiles.beam;

import org.esa.beam.dataio.netcdf.ProfileReadContext;
import org.esa.beam.dataio.netcdf.ProfileWriteContext;
import org.esa.beam.dataio.netcdf.metadata.ProfilePartIO;
import org.esa.beam.dataio.netcdf.util.DataTypeUtils;
import org.esa.beam.dataio.netcdf.util.MetadataUtils;
import org.esa.beam.framework.datamodel.MetadataAttribute;
import org.esa.beam.framework.datamodel.MetadataElement;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.nc2.Attribute;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFileWriteable;
import ucar.nc2.Variable;

import java.io.IOException;

public class BeamMetadataPart extends ProfilePartIO {

    private static final String SPLITTER = ":";
    private static final String METADATA_VARIABLE = "metadata";
    private static final String DESCRIPTION_SUFFIX = ".descr";
    private static final String UNIT_SUFFIX = ".unit";

    @Override
    public void decode(ProfileReadContext ctx, Product p) throws IOException {
        final NetcdfFile netcdfFile = ctx.getNetcdfFile();
        Variable metadata = netcdfFile.getRootGroup().findVariable(METADATA_VARIABLE);
        final MetadataElement metadataRoot = p.getMetadataRoot();
        MetadataUtils.readNetcdfMetadata(netcdfFile, p.getMetadataRoot());
        if (metadata != null) {
            for (Attribute attribute : metadata.getAttributes()) {
                String attrName = attribute.getName();
                if (attrName.startsWith(SPLITTER)) {
                    attrName = attrName.substring(1, attrName.length());
                }
                if (attrName.contains(SPLITTER)) {
                    String prefix = attrName.split(SPLITTER)[0];
                    readMetadata(attribute, metadataRoot, prefix);
                } else {
                    ProductData attributeValue = extractValue(attribute);
                    metadataRoot.addAttribute(new MetadataAttribute(attrName, attributeValue, true));
                }
            }
        }
    }

    private void readMetadata(Attribute attribute, MetadataElement metadataRoot, String prefix) {
        // create new subgroup or take existing one
        String[] splittedPrefix = prefix.split(SPLITTER);
        String metaDataElementName = prefix;
        if (splittedPrefix.length > 1) {
            metaDataElementName = splittedPrefix[1];
        }
        MetadataElement metadataElement = metadataRoot.getElement(metaDataElementName);
        if (metadataElement == null) {
            metadataElement = new MetadataElement(metaDataElementName);
            metadataRoot.addElement(metadataElement);
        }
        // cut prefix of attribute name
        String temp = attribute.getName().replaceFirst(SPLITTER + prefix, "");
        if (temp.startsWith(SPLITTER)) {
            temp = temp.substring(1, temp.length());
        }
        String[] splittedAttrName = temp.split(SPLITTER);
        temp = splittedAttrName[0];
        if (splittedAttrName.length > 1) {
            // recursive call
            readMetadata(attribute, metadataElement, prefix + SPLITTER + temp);
        } else {
            // attribute is leaf, add attribute into subgroup
            String newAttributeName = attribute.getName().replaceFirst(prefix, "").replace(SPLITTER, "");
            if (newAttributeName.endsWith(UNIT_SUFFIX)) {
                // setting the unit this way requires that it is written AFTER its attribute
                MetadataAttribute anAttribute = metadataElement.getAttribute(newAttributeName.replace(UNIT_SUFFIX, ""));
                String value = attribute.getStringValue();
                if (value != null) {
                    anAttribute.setUnit(value);
                }
            } else if (newAttributeName.endsWith(DESCRIPTION_SUFFIX)) {
                // setting the description this way requires that it is written AFTER its attribute
                MetadataAttribute anAttribute = metadataElement.getAttribute(newAttributeName.replace(
                        DESCRIPTION_SUFFIX, ""));
                String value = attribute.getStringValue();
                if (value != null) {
                    anAttribute.setDescription(value);
                }
            } else {
                ProductData attributeValue = extractValue(attribute);
                MetadataAttribute newAttribute = new MetadataAttribute(newAttributeName, attributeValue, true);
                metadataElement.addAttribute(newAttribute);
            }
        }
    }

    private ProductData extractValue(Attribute attribute) {
        ProductData attributeValue;
        int productDataType = DataTypeUtils.getEquivalentProductDataType(attribute.getDataType(), false, false);
        if (productDataType == ProductData.TYPE_ASCII) {
            attributeValue = ProductData.createInstance(attribute.getStringValue());
        } else {
            attributeValue = ProductData.createInstance(productDataType, attribute.getValues().copyTo1DJavaArray());
        }
        return attributeValue;
    }

    @Override
    public void preEncode(ProfileWriteContext ctx, Product p) throws IOException {
        final MetadataElement root = p.getMetadataRoot();
        if (root != null) {
            final NetcdfFileWriteable ncFile = ctx.getNetcdfFileWriteable();
            final Variable variable = ncFile.addVariable(METADATA_VARIABLE, DataType.BYTE, "");
            writeMetadataElement(root, variable, "");
        }
    }

    private void writeMetadataElement(MetadataElement element, Variable ncVariable, String prefix) throws IOException {
        for (int i = 0; i < element.getNumAttributes(); i++) {
            MetadataAttribute attribute = element.getAttributeAt(i);
            writeMetadataAttribute(attribute, ncVariable, prefix);
        }
        for (int i = 0; i < element.getNumElements(); i++) {
            MetadataElement subElement = element.getElementAt(i);
            final String subElementName = subElement.getName();
            if (!isGlobalAttributesElement(subElementName)) {
                writeMetadataElement(subElement, ncVariable, prefix + SPLITTER + subElementName);
            }
        }
    }

    private boolean isGlobalAttributesElement(String subElementName) {
        return MetadataUtils.GLOBAL_ATTRIBUTES.equals(subElementName) ||
               MetadataUtils.VARIABLE_ATTRIBUTES.equals(subElementName);
    }

    private void writeMetadataAttribute(MetadataAttribute metadataAttr, Variable ncVariable, String prefix) {
        final ProductData productData = metadataAttr.getData();
        final String ncAttributeName = prefix + SPLITTER + metadataAttr.getName();
        if (productData instanceof ProductData.ASCII || productData instanceof ProductData.UTC) {
            ncVariable.addAttribute(new Attribute(ncAttributeName, productData.getElemString()));
        } else {
            ncVariable.addAttribute(new Attribute(ncAttributeName, Array.factory(productData.getElems())));
        }
        if (metadataAttr.getUnit() != null) {
            ncVariable.addAttribute(new Attribute(ncAttributeName + UNIT_SUFFIX, metadataAttr.getUnit()));
        }
        if (metadataAttr.getDescription() != null) {
            ncVariable.addAttribute(new Attribute(ncAttributeName + DESCRIPTION_SUFFIX, metadataAttr.getDescription()));
        }
    }
}
