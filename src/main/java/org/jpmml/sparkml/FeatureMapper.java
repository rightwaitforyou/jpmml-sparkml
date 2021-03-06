/*
 * Copyright (c) 2016 Villu Ruusmann
 *
 * This file is part of JPMML-SparkML
 *
 * JPMML-SparkML is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-SparkML is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-SparkML.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.sparkml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Iterables;
import org.apache.spark.ml.Model;
import org.apache.spark.ml.PredictionModel;
import org.apache.spark.ml.Transformer;
import org.apache.spark.ml.classification.ClassificationModel;
import org.apache.spark.ml.classification.GBTClassificationModel;
import org.apache.spark.ml.clustering.KMeansModel;
import org.apache.spark.ml.param.shared.HasFeaturesCol;
import org.apache.spark.ml.param.shared.HasLabelCol;
import org.apache.spark.ml.param.shared.HasOutputCol;
import org.apache.spark.sql.types.IntegerType;
import org.apache.spark.sql.types.NumericType;
import org.apache.spark.sql.types.StringType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.dmg.pmml.DataDictionary;
import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.DerivedField;
import org.dmg.pmml.Expression;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.OpType;
import org.dmg.pmml.PMML;
import org.dmg.pmml.TransformationDictionary;
import org.jpmml.converter.ContinuousFeature;
import org.jpmml.converter.Feature;
import org.jpmml.converter.FeatureSchema;
import org.jpmml.converter.ListFeature;
import org.jpmml.converter.PMMLUtil;
import org.jpmml.converter.PseudoFeature;

public class FeatureMapper {

	private StructType schema = null;

	private Map<String, List<Feature>> columnFeatures = new LinkedHashMap<>();

	private Map<FieldName, DataField> dataFields = new LinkedHashMap<>();

	private Map<FieldName, DerivedField> derivedFields = new LinkedHashMap<>();


	public FeatureMapper(StructType schema){
		this.schema = schema;
	}

	public PMML encodePMML(){

		if(!Collections.disjoint(this.dataFields.keySet(), this.derivedFields.keySet())){
			throw new IllegalArgumentException();
		}

		List<DataField> dataFields = new ArrayList<>(this.dataFields.values());
		List<DerivedField> derivedFields = new ArrayList<>(this.derivedFields.values());

		DataDictionary dataDictionary = new DataDictionary();
		(dataDictionary.getDataFields()).addAll(dataFields);

		TransformationDictionary transformationDictionary = null;
		if(derivedFields.size() > 0){
			transformationDictionary = new TransformationDictionary();
			(transformationDictionary.getDerivedFields()).addAll(derivedFields);
		}

		PMML pmml = new PMML("4.2", PMMLUtil.createHeader("JPMML-SparkML", "1.0-SNAPSHOT"), dataDictionary)
			.setTransformationDictionary(transformationDictionary);

		return pmml;
	}

	public void append(FeatureConverter<?> converter){
		Transformer transformer = converter.getTransformer();

		List<Feature> features = converter.encodeFeatures(this);

		if(transformer instanceof HasOutputCol){
			HasOutputCol hasOutputCol = (HasOutputCol)transformer;

			String outputCol = hasOutputCol.getOutputCol();

			this.columnFeatures.put(outputCol, features);
		}
	}

	public FeatureSchema createSchema(Model<?> model){
		FieldName targetField;
		List<String> targetCategories = null;

		if(model instanceof PredictionModel){
			HasLabelCol hasLabelCol = (HasLabelCol)model;

			Feature feature = getOnlyFeature(hasLabelCol.getLabelCol());

			targetField = feature.getName();

			if((model instanceof ClassificationModel) || (model instanceof GBTClassificationModel)){
				ListFeature listFeature = (ListFeature)feature;

				targetCategories = listFeature.getValues();
			}
		} else

		if(model instanceof KMeansModel){
			targetField = null;
		} else

		{
			throw new IllegalArgumentException();
		}

		List<FieldName> activeFields = new ArrayList<>(this.dataFields.keySet());
		activeFields.remove(targetField);

		HasFeaturesCol hasFeaturesCol = (HasFeaturesCol)model;

		List<Feature> features = getFeatures(hasFeaturesCol.getFeaturesCol());

		if(model instanceof PredictionModel){
			PredictionModel<?, ?> predictionModel = (PredictionModel<?, ?>)model;

			if(features.size() != predictionModel.numFeatures()){
				throw new IllegalArgumentException();
			}
		}

		FeatureSchema result = new FeatureSchema(targetField, targetCategories, activeFields, features);

		return result;
	}

	public Feature getOnlyFeature(String column){
		List<Feature> features = getFeatures(column);

		return Iterables.getOnlyElement(features);
	}

	public List<Feature> getFeatures(String column){
		List<Feature> features = this.columnFeatures.get(column);

		if(features == null){
			FieldName name = FieldName.create(column);

			DataField dataField = this.dataFields.get(name);
			if(dataField == null){
				dataField = createDataField(name);
			}

			Feature feature;

			DataType dataType = dataField.getDataType();
			switch(dataType){
				case INTEGER:
				case FLOAT:
				case DOUBLE:
					feature = new ContinuousFeature(dataField);
					break;
				default:
					feature = new PseudoFeature(dataField);
					break;
			}

			return Collections.singletonList(feature);
		}

		return features;
	}

	public List<Feature> getFeatures(String column, int[] indices){
		List<Feature> features = getFeatures(column);

		List<Feature> result = new ArrayList<>();

		for(int i = 0; i < indices.length; i++){
			int index = indices[i];

			Feature feature = features.get(index);

			result.add(feature);
		}

		return result;
	}

	public DataField getDataField(FieldName name){
		return this.dataFields.get(name);
	}

	public DataField createDataField(FieldName name){
		StructField field = this.schema.apply(name.getValue());

		OpType opType;
		DataType dataType;

		org.apache.spark.sql.types.DataType sparkDataType = field.dataType();
		if(sparkDataType instanceof NumericType){
			opType = OpType.CONTINUOUS;
			dataType = (sparkDataType instanceof IntegerType ? DataType.INTEGER : DataType.DOUBLE);
		} else

		if(sparkDataType instanceof StringType){
			opType = OpType.CATEGORICAL;
			dataType = DataType.STRING;
		} else

		{
			throw new IllegalArgumentException();
		}

		return createDataField(name, opType, dataType);
	}

	public DataField createDataField(FieldName name, OpType opType, DataType dataType){
		DataField dataField = new DataField(name, opType, dataType);

		this.dataFields.put(dataField.getName(), dataField);

		return dataField;
	}

	public DerivedField getDerivedField(FieldName name){
		return this.derivedFields.get(name);
	}

	public DerivedField createDerivedField(FieldName name, OpType opType, DataType dataType, Expression expression){
		DerivedField derivedField = new DerivedField(opType, dataType)
			.setName(name)
			.setExpression(expression);

		this.derivedFields.put(derivedField.getName(), derivedField);

		return derivedField;
	}
}