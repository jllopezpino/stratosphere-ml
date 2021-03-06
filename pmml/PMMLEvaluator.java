/***********************************************************************************************************************
 *
 * Copyright (C) 2014 by Jose Luis Lopez Pino (jllopezpino@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/
package eu.stratosphere.ml.pmml;

import java.io.DataInputStream;
import java.io.Serializable;
import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.transform.Source;

import org.dmg.pmml.FieldName;
import org.dmg.pmml.OutputField;
import org.dmg.pmml.PMML;
import org.dmg.pmml.ResultFeatureType;
import org.jpmml.evaluator.Evaluator;
import org.jpmml.evaluator.EvaluatorUtil;
import org.jpmml.evaluator.ModelEvaluatorFactory;
import org.jpmml.evaluator.FieldValue;
import org.jpmml.manager.PMMLManager;
import org.jpmml.model.ImportFilter;
import org.jpmml.model.JAXBUtil;
import org.xml.sax.InputSource;

import eu.stratosphere.api.java.DataSet;
import eu.stratosphere.api.java.ExecutionEnvironment;
import eu.stratosphere.api.java.functions.MapFunction;
import eu.stratosphere.api.java.tuple.Tuple2;
import eu.stratosphere.api.java.tuple.Tuple3;
import eu.stratosphere.configuration.Configuration;

@SuppressWarnings("serial")
public class PMMLEvaluator {

	/**
	 * Input object of the classifier
	 */
	public static class Instance implements Serializable {

		private static String INSTANCE_SEPARATOR = ",";
		
		private double[] features;
		private int id;
		
		public Instance() {}
		
		public Instance(String line){
			
			String[] array = line.split(INSTANCE_SEPARATOR, 2);
			
			id = new Integer(array[0]).intValue();
			setFeatures(array[1]);
			
		}

		public Instance(int id, String featuresLine) {
			this.id = id;

			setFeatures(featuresLine);
		}

		public Instance(double[] features) {
			this.features = features;
		}

		@Override
		public String toString() {
			return id + "," + this.getStringFeatures();
		}
		
		public String getStringFeatures(){
			int i;
			StringBuffer resultingString = new StringBuffer();
			
			for (i = 0; i < features.length - 1; i++) {
				resultingString.append(features[i] + ",");
			}
			resultingString.append(features[i]);

			return resultingString.toString();
		}

		public double[] getFeatures() {
			return features;
		}
		
		public void setFeatures(String featuresLine){
			
			String[] inputFeatures = featuresLine.split(",");
			
			features = new double[inputFeatures.length];

			for (int i = 0; i < inputFeatures.length; i++) {
				features[i] = Double.parseDouble(inputFeatures[i]);
			}
		}
		
		public int getId(){
			return id;
		}

		// we assume that the features are in the same order as the fields
		// defined in the .pmml file
		public Map<FieldName, FieldValue> toMap(Evaluator evaluator) {

			List<FieldName> names = evaluator.getActiveFields();

			Map<FieldName, FieldValue> result = new LinkedHashMap<FieldName, FieldValue>();

			for (int i = 0; i < names.size() - 1; i++) {
				FieldName name = names.get(i);

				FieldValue value = EvaluatorUtil.prepare(evaluator, name,
						features[i]);
				result.put(name, value);
			}

			return result;
		}

	}

	/**
	 *
	 */
	public static final class Classify extends
			MapFunction<String, Tuple3<Integer, String, String>> {
		private static final long serialVersionUID = 1L;

		private Evaluator evaluator = null;
		private FieldName predictedField;
		private String modelURI; 

		public Classify(String modelURI) {
			this.modelURI = modelURI;
		}

		private void setPredictedField() {
			Iterator<FieldName> iterOutputs = evaluator.getOutputFields()
					.iterator();
			while (iterOutputs.hasNext()) {
				// ResultFeatureType.PREDICTED_VALUE

				FieldName next = iterOutputs.next();
				OutputField of = evaluator.getOutputField(next);

				ResultFeatureType resultFeature = of.getFeature();

				if (resultFeature == ResultFeatureType.PREDICTED_VALUE) {
					predictedField = next;
				}

			}

		}

		/**
		 * Before applying the map function, we read the model from the given
		 * url
		 */
		@Override
		public void open(Configuration parameters) throws Exception {

			// First, we need to read the file from the HDFS file system
			eu.stratosphere.core.fs.Path pt = new eu.stratosphere.core.fs.Path(modelURI);
			String defaultFS = "hdfs://localhost/";

			URI geller = new URI(defaultFS);
			eu.stratosphere.core.fs.FileSystem fs = eu.stratosphere.core.fs.FileSystem
					.get(geller);

			// After that, we load the pmml model
			PMML pmml;
			DataInputStream d = new DataInputStream(fs.open(pt));

			try {
				Source source = ImportFilter.apply(new InputSource(d));

				pmml = JAXBUtil.unmarshalPMML(source);
			} finally {
				d.close();
			}

			// And finally, we create the evaluator
			PMMLManager pmmlManager = new PMMLManager(pmml);
			evaluator = (Evaluator) pmmlManager.getModelManager(null,
					ModelEvaluatorFactory.getInstance());

			setPredictedField();

		}
		
		private Map<FieldName, FieldValue> tuple2Map(Tuple2<Integer, String> tuple, Evaluator evaluator) {

			List<FieldName> names = evaluator.getActiveFields();

			Map<FieldName, FieldValue> result = new LinkedHashMap<FieldName, FieldValue>();
			
			String line = tuple.getField(1);
			String[] inputFeatures = line.split(",");

			for (int i = 0; i < names.size() - 1; i++) {
				FieldName name = names.get(i);
				Double feature = Double.parseDouble(inputFeatures[i]);
				
				System.out.println("Feature read: " + feature);

				FieldValue value = EvaluatorUtil.prepare(evaluator, name, feature);
				result.put(name, value);
			}
			
			

			return result;
		
		}

		@Override
		public Tuple3<Integer, String, String> map(String observation) throws Exception {

			Instance p = new Instance(observation);
			
			Map<FieldName, FieldValue> arguments = p.toMap(evaluator);

			Map<FieldName, ?> classified = evaluator.evaluate(arguments);

			String label = String.valueOf(classified.get(predictedField));
			
			Integer returnId = p.getId();
			String returnFeatures = p.getStringFeatures();

			// emit a new tuple with the label
			return (new Tuple3<Integer, String, String>(returnId, label, returnFeatures));

		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.err
					.println("Usage: PMMLEvaluator <input path> <output path> <model path>");
			return;
		}

		final String input = args[0];
		final String output = args[1];
		final String model = args[2];

		ExecutionEnvironment env = ExecutionEnvironment
				.getExecutionEnvironment();
		

		Classify classifyOperator = new Classify(model);
		

		// I think I should use this
		// env.readCsvFile(filePath)

		

		
		DataSet<String> inputData = env.readTextFile(input);
		
		DataSet<Tuple3<Integer, String, String>> resultData = inputData.map(classifyOperator);
		
		
		
		
		
//		DataSet<Instance> instancesToClassify = env.fromElements(
//				new Instance("1, 5.1, 3.5, 1.4, 0.2"), 
//				new Instance("2, 4.9, 3.0, 1.4, 0.2"),
//				new Instance("3, 7.0, 3.2, 4.7, 1.4"), 
//				new Instance("4, 6.4, 3.2, 4.5, 1.5"), 
//				new Instance("5, 8.0, 3.3, 3, 2"));
//		
//		
//		DataSet<Tuple3<Integer, String, String>> resultData = instancesToClassify
//				.map(classifyOperator);

		resultData.writeAsCsv(output);
		
		
		
		env.execute("PMML evaluator example");
	}
}
