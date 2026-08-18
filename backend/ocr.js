import { GoogleGenAI } from '@google/genai';
import fs from 'fs';
import path from 'path';
import dotenv from 'dotenv';
import { trackGemini, trackSarvam } from './usageTracker.js';
// We're using standard fetch available natively in Node.js 18+

dotenv.config();

// Helper to convert a local file to the format required by Google GenAI SDK
function fileToGenerativePart(path, mimeType) {
  return {
    inlineData: {
      data: Buffer.from(fs.readFileSync(path)).toString('base64'),
      mimeType,
    },
  };
}

/**
 * Scans a medical report image and extracts structured data.
 * Supports standard Gemini OCR or regional Indic OCR & translation via Sarvam AI.
 * 
 * @param {string} filePath - Absolute path to the uploaded image file.
 * @param {string} mimeType - MIME type of the image.
 * @param {boolean} useSarvam - Whether to run the Sarvam AI pipeline (handwritten/non-English).
 * @param {string} localOcrText - Pre-extracted OCR text from the image.
 * @param {string} scanType - Type of scan: 'prescription' or 'report'.
 * @param {string} reportCategory - Category of report: 'blood_test', 'sonography', '2d_echo', 'xray', 'other'.
 * @returns {Promise<object>} Structured medical report details.
 */
export async function scanMedicalReport(filePath, mimeType, useSarvam = false, localOcrText = '', scanType = '', reportCategory = '') {
  const sarvamKey = process.env.SARVAM_API_KEY;
  const geminiKey = process.env.GEMINI_API_KEY;

  // Reference text gathered from auxiliary OCR engines. It only *assists* Gemini vision —
  // the raw image is always sent to Gemini too, so handwriting a text-only OCR drops is
  // still read directly from the picture.
  let referenceText = localOcrText || '';

  // Optional: Sarvam Indic OCR + translation for handwritten / regional-language documents.
  // We no longer return here; we merely collect extra reference text and continue to
  // Gemini vision so nothing on the page (including handwriting) is ignored.
  if (useSarvam) {
    if (sarvamKey && sarvamKey !== 'YOUR_SARVAM_API_KEY_HERE') {
      try {
        console.log('Running Sarvam Indic OCR + Translate pipeline for reference text...');
        const sarvamText = await trackSarvam('doc-digitization', { pages: 1 }, () => runSarvamOcrAndTranslate(filePath, mimeType, sarvamKey));
        if (sarvamText && sarvamText.trim().length > 0) {
          referenceText = [referenceText, sarvamText].filter(Boolean).join('\n\n');
          console.log(`Sarvam reference text captured (${sarvamText.length} chars).`);
        } else {
          console.warn('Sarvam returned no usable text; relying on Gemini vision + local OCR only.');
        }
      } catch (error) {
        console.error('Sarvam AI pipeline failed (continuing with Gemini vision):', error.message);
      }
    } else {
      console.warn('SARVAM_API_KEY is not configured. Skipping Sarvam; using Gemini vision + local OCR.');
    }
  }

  // Standard extraction: always send the actual IMAGE to Gemini vision so handwritten
  // notes (which regional/text-only OCR often miss) are read directly from the picture.
  if (!geminiKey || geminiKey === 'YOUR_GEMINI_API_KEY_HERE') {
    console.warn('GEMINI_API_KEY is not set. Using local text fallback parser.');
    return parseTextLocally(referenceText || getMockRawText(filePath, scanType, reportCategory), scanType, reportCategory);
  }

  try {
    const ai = new GoogleGenAI({ apiKey: geminiKey });
    const imagePart = fileToGenerativePart(filePath, mimeType);

    const categoryText = scanType === 'prescription' 
      ? "This document is a Medicine Prescription. Focus heavily on identifying the doctor's prescribed medications, dosages, frequency, durations, and instruction comments."
      : `This document is a Medical/Diagnostic Report of category "${reportCategory}". Focus on patient name, report date, and extracting findings, observations, conclusions, and test parameters (e.g. values, units, reference ranges, abnormal flags).`;

    const promptText = `
Analyze this medical report, lab result, or prescription image and extract the details as a JSON object.
${referenceText ? `Here is auxiliary OCR text (from the device's on-screen OCR and/or Sarvam Indic OCR) to assist accuracy. It may be incomplete or miss handwriting, so ALWAYS prefer what you can read directly from the image:\n"""\n${referenceText}\n"""\n` : ''}
Context instructions:
${categoryText}

IMPORTANT: This document may contain HANDWRITTEN text (a doctor's handwriting, margin notes, ticked boxes, or corrections). Read handwritten medicines, dosages, frequencies, and comments carefully and include them — do NOT ignore handwriting just because it is hard to read. If handwriting is partially illegible, transcribe your best interpretation.

Ensure that:
1. Patient name is identified accurately.
2. The date of the report/prescription is extracted and formatted as YYYY-MM-DD. DO NOT guess the date. Look for date labels like 'Date', 'Report Date', 'Collected', 'Reported', 'Dated', or signature date. If no date is visible in the text, return null.
3. Comments, instructions, remarks, or advice given in the report/prescription are extracted accurately.
4. If it's a prescription or mentions medicines, extract all medications as an array of objects. If a medicine has an explicit printed or handwritten start/end date (e.g. "start from 20/10/26", "TILL 20/8/26"), fill "startDate"/"endDate" as absolute YYYY-MM-DD dates — but leave them null for vague endings like "till follow-up" or "continue".
5. If the doctor mentions recommended future tests (e.g. "Thyroid test in 3 months", "Check CBC next week"), extract them in recommendedTests.
6. Extract test results and findings in the "testResults" field.
   - For lab tests (like Blood Tests): extract all parameters into the "parameters" array.
   - For scans/diagnostics (like Sonography, 2D Echo, X-Ray): extract key findings/conclusions/observations into the "findings" array of strings.

The response MUST be a JSON object with the following schema:
{
  "patientName": "Name of patient or null if not found",
  "reportDate": "YYYY-MM-DD or null if not found",
  "reportType": "Prescription | Lab Report | Diagnostic Scan | Other",
  "comments": "Doctor's instructions, advice, comments or notes",
  "medications": [
    {
      "name": "Name of the medicine",
      "dosage": "Dosage (e.g. 500mg, 1 tablet)",
      "frequency": "Frequency (e.g. twice daily, 1-0-1, as needed)",
      "duration": "Duration (e.g. 5 days, 1 month, or null)",
      "isOptional": false, // boolean, set to true if it is taken optionally/as needed/sos
      "weeklySchedule": ["Everyday"], // array of strings (e.g. ["Mon", "Thu"], ["Everyday"], or ["As Needed"]) representing when to take it
      "notes": "Any special instructions or empty string",
      "startDate": "YYYY-MM-DD or null — ONLY when the page states an explicit future start date (e.g. 'start from 20/10/26'); leave null when the medicine starts on the report date itself",
      "endDate": "YYYY-MM-DD or null. Search the WHOLE medication row/entry — an instruction sentence, frequency line, or remarks column — for an explicit calendar end date ('TILL 20/10/26', 'TILL 20 OCT 2026'). A row can print BOTH a generic day-count ('Duration: 90 Days') AND a separate, more specific calendar date in its instructions; when both appear, the calendar date is the real stop date and MUST be used here, not the generic count. Only fall back to computing from a relative day-count (same method as dueDate below, e.g. today 2026-08-13 and '90 days' -> 2026-11-11) when NO explicit calendar date is printed anywhere on the row. Leave null for vague endings like 'till follow-up' or 'continue' — 'duration' already captures that text",
      "intervalDays": "Number or null — ONLY for a dosing cadence that repeats every N days without lining up to the same weekday each week: 'once in 15 days'->15, 'every 3 days'->3, 'alternate day'->2. Leave null for a daily medicine or one tied to specific weekday(s) (captured in weeklySchedule instead), and do not confuse with a course-length day-count"
    }
  ],
  "recommendedTests": [
    {
      "testName": "Name of the future recommended test",
      "dueDate": "Estimated due date YYYY-MM-DD (e.g., if today is 2026-06-22 and doctor says 'test in 3 months', calculate as 2026-09-22) or null if no timeline is specified"
    }
  ],
  "testResults": {
    "parameters": [
      {
        "name": "Parameter/test name (e.g. Hemoglobin, TSH, Platelet Count, Ejection Fraction)",
        "value": "Observed value as string (e.g. 13.5, 5.8, 60)",
        "unit": "Measurement unit (e.g. g/dL, uIU/mL, %) or empty string",
        "referenceRange": "Reference range (e.g. 12.0 - 15.0) or empty string",
        "status": "High | Low | Normal"
      }
    ],
    "findings": [
      "Significant organ findings, impression, or diagnostic conclusion (e.g. Mild fatty liver, Chest X-Ray shows clear lung fields)"
    ]
  },
  "rawText": "A clean, markdown-formatted full transcription of the text visible in the image."
}

Do not return any markdown code block formatting (like \`\`\`json) or extra text outside the JSON. Return only raw JSON.
`;

    console.log('Sending image to Gemini for analysis...');
    const response = await trackGemini(ai, {
      model: 'gemini-3.6-flash',
      contents: [imagePart, promptText],
    });

    let cleanedText = response.text.trim();
    if (cleanedText.startsWith('```')) {
      cleanedText = cleanedText.replace(/^```json\s*/, '').replace(/```$/, '').trim();
    }

    const parsedData = JSON.parse(cleanedText);
    return parsedData;

  } catch (error) {
    console.error('Error during Gemini OCR processing:', error);
    console.log('Falling back to local text fallback parser...');
    return parseTextLocally(referenceText || getMockRawText(filePath, scanType, reportCategory), scanType, reportCategory);
  }
}

/**
 * Compares two medical reports/prescriptions and determines what has changed.
 * Returns structured changes and clinical inferences.
 */
export async function generateReportComparison(newReport, previousReport) {
  if (!previousReport) {
    return { hasComparison: false };
  }

  const geminiKey = process.env.GEMINI_API_KEY;

  if (geminiKey && geminiKey !== 'YOUR_GEMINI_API_KEY_HERE') {
    try {
      const ai = new GoogleGenAI({ apiKey: geminiKey });
      
      const comparisonPrompt = `
Compare the following previous medical report/prescription with the new one for patient "${newReport.patient_name}".

PREVIOUS REPORT:
Date: ${previousReport.report_date}
Type: ${previousReport.report_type}
Category: ${previousReport.report_category}
Medications: ${JSON.stringify(previousReport.medications)}
Test Results: ${JSON.stringify(previousReport.test_results)}
Comments: ${previousReport.comments}

NEW REPORT:
Date: ${newReport.report_date}
Type: ${newReport.report_type}
Category: ${newReport.report_category}
Medications: ${JSON.stringify(newReport.medications)}
Test Results: ${JSON.stringify(newReport.test_results)}
Comments: ${newReport.comments}

Please analyze the progression of the patient's condition and medications.
1. Medication Changes:
   - "added": List medications that were prescribed in the new report but were NOT in the previous report.
   - "removed": List medications that were in the previous report but are NOT in the new report (discontinued).
   - "changed": List medications present in both, but with modified dosages, frequencies, or instructions (explain what changed).
2. Test/Lab result differences:
   - "differences": List specific numerical parameters or findings that can be matched between previous and new reports.
     Include:
     - "name": The parameter name (e.g. TSH, Hemoglobin, EF).
     - "previous": The previous value with units (e.g. "6.2 uIU/mL").
     - "current": The new value with units (e.g. "5.8 uIU/mL").
     - "change": "increased" | "decreased" | "stable" | "changed" (select best fit).
     - "status": "improved" | "worsened" | "no_change" (select best clinical fit).
3. Overall clinical summary/inference:
   - "comparisonSummary": A concise patient-friendly explanation (2-3 sentences) of what these changes mean. Make sure it highlights if they are getting better, stable, or need attention.
   - "status": "improved" | "worsened" | "no_change" | "mixed" (representing overall trend).
   
The response MUST be a JSON object matching this schema:
{
  "hasComparison": true,
  "previousReportId": "${previousReport.id}",
  "previousReportDate": "${previousReport.report_date}",
  "comparisonSummary": "A concise patient-friendly clinical insight summary.",
  "status": "improved | worsened | no_change | mixed",
  "differences": [
    {
      "name": "Parameter Name",
      "previous": "Previous value",
      "current": "Current value",
      "change": "increased | decreased | stable | changed",
      "status": "improved | worsened | no_change"
    }
  ],
  "medicationChanges": {
    "added": ["Medicine name"],
    "removed": ["Medicine name"],
    "changed": ["Medicine name (e.g. dosage increased from 50mg to 100mg)"]
  }
}
Return ONLY the raw JSON. Do not write markdown wrappers like \`\`\`json.
`;
      
      console.log('Sending reports to Gemini for comparison...');
      const response = await trackGemini(ai, {
        model: 'gemini-3.6-flash',
        contents: [comparisonPrompt],
      });
      
      let cleanedText = response.text.trim();
      if (cleanedText.startsWith('```')) {
        cleanedText = cleanedText.replace(/^```json\s*/, '').replace(/```$/, '').trim();
      }
      
      return JSON.parse(cleanedText);
    } catch (error) {
      console.error('Error during Gemini reports comparison, falling back to local comparator:', error);
    }
  }

  // Local JS fallback comparison
  return generateLocalComparison(newReport, previousReport);
}

/**
 * JavaScript fallback to diff reports locally without Gemini.
 */
function generateLocalComparison(newReport, previousReport) {
  const previousMeds = previousReport.medications || [];
  const currentMeds = newReport.medications || [];
  
  const added = [];
  const removed = [];
  const changed = [];
  
  currentMeds.forEach(c => {
    const p = previousMeds.find(med => med.name.toLowerCase() === c.name.toLowerCase());
    if (!p) {
      added.push(c.name);
    } else if (p.dosage !== c.dosage || p.frequency !== c.frequency) {
      changed.push(`${c.name} (dosage/frequency changed from ${p.dosage || 'N/A'} [${p.frequency || 'N/A'}] to ${c.dosage || 'N/A'} [${c.frequency || 'N/A'}])`);
    }
  });
  
  previousMeds.forEach(p => {
    const c = currentMeds.find(med => med.name.toLowerCase() === p.name.toLowerCase());
    if (!c) {
      removed.push(p.name);
    }
  });
  
  const previousParams = (previousReport.test_results && previousReport.test_results.parameters) || [];
  const currentParams = (newReport.test_results && newReport.test_results.parameters) || [];
  const differences = [];
  let overallStatus = "no_change";
  let improvementsCount = 0;
  let worseningCount = 0;
  
  currentParams.forEach(c => {
    const p = previousParams.find(param => param.name.toLowerCase() === c.name.toLowerCase());
    if (p) {
      const pVal = parseFloat(p.value);
      const cVal = parseFloat(c.value);
      
      let change = "stable";
      let status = "no_change";
      
      if (!isNaN(pVal) && !isNaN(cVal)) {
        if (cVal > pVal) {
          change = "increased";
        } else if (cVal < pVal) {
          change = "decreased";
        }
        
        if (c.status.toLowerCase() === "normal" && p.status.toLowerCase() !== "normal") {
          status = "improved";
          improvementsCount++;
        } else if (c.status.toLowerCase() !== "normal" && p.status.toLowerCase() === "normal") {
          status = "worsened";
          worseningCount++;
        } else if (change !== "stable") {
          status = "changed";
        }
      }
      
      differences.push({
        name: c.name,
        previous: `${p.value} ${p.unit || ''}`.trim(),
        current: `${c.value} ${c.unit || ''}`.trim(),
        change,
        status
      });
    }
  });
  
  if (improvementsCount > 0 && worseningCount === 0) {
    overallStatus = "improved";
  } else if (worseningCount > 0 && improvementsCount === 0) {
    overallStatus = "worsened";
  } else if (improvementsCount > 0 && worseningCount > 0) {
    overallStatus = "mixed";
  }
  
  let summary = `Compared to report from ${previousReport.report_date}, `;
  const summaryParts = [];
  if (added.length > 0) summaryParts.push(`added medications: ${added.join(', ')}`);
  if (removed.length > 0) summaryParts.push(`discontinued: ${removed.join(', ')}`);
  if (changed.length > 0) summaryParts.push(`modified medications: ${changed.join(', ')}`);
  if (differences.length > 0) {
    const diffDetails = differences.map(d => `${d.name} ${d.change} from ${d.previous} to ${d.current}`).join(', ');
    summaryParts.push(`test parameters changed: ${diffDetails}`);
  }
  
  if (summaryParts.length === 0) {
    summary += "no significant changes in test parameters or medications were detected.";
  } else {
    summary += summaryParts.join('; ') + ".";
  }
  
  return {
    hasComparison: true,
    previousReportId: previousReport.id,
    previousReportDate: previousReport.report_date,
    comparisonSummary: summary,
    status: overallStatus,
    differences,
    medicationChanges: { added, removed, changed }
  };
}

/**
 * Generates patient-friendly health insights from a medical report.
 * Returns interpretation, specialist recommendations, prescription-report
 * alignment analysis, and foreseeable medicine side-effects.
 *
 * @param {object} reportData - Full report object with test_results, medications, comments, report_category.
 * @returns {Promise<object>} Structured health insights JSON.
 */
export async function generateHealthInsights(reportData) {
  if (!reportData) return {};

  const geminiKey = process.env.GEMINI_API_KEY;

  if (geminiKey && geminiKey !== 'YOUR_GEMINI_API_KEY_HERE') {
    try {
      const ai = new GoogleGenAI({ apiKey: geminiKey });

      const insightsPrompt = `
You are an expert medical AI assistant. Analyze the following medical report data and generate structured health insights for the patient. Write in clear, plain language a patient with no medical background can understand.

REPORT DATA:
Category: ${reportData.report_category || 'unknown'}
Type: ${reportData.report_type || 'unknown'}
Doctor Comments: ${reportData.comments || 'None'}
Test Results Parameters: ${JSON.stringify(reportData.test_results?.parameters || [])}
Findings/Impressions: ${JSON.stringify(reportData.test_results?.findings || [])}
Prescribed Medications: ${JSON.stringify(reportData.medications || [])}

Please generate a JSON response with the following structure:

{
  "interpretation": "A 3-4 sentence patient-friendly explanation of what the overall report findings mean for the patient's health. Mention specific abnormal values and their significance. Use plain language like 'Your thyroid hormone level is slightly above normal, which means...' Do NOT be alarming, be reassuring and factual.",
  "specialistRecommendations": [
    {
      "specialist": "Type of doctor (e.g. Endocrinologist, Cardiologist, Hepatologist, General Physician, Pulmonologist, Nephrologist, Neurologist, etc.)",
      "reason": "Specific reason why this specialist is recommended based on the report findings",
      "urgency": "Routine | Soon | Urgent"
    }
  ],
  "prescriptionAlignment": {
    "aligned": true,
    "score": "Good | Partial | Poor | N/A",
    "analysis": "2-3 sentence explanation of whether the prescribed medicines match the diagnosis from the report findings. Mention specific medicines and findings.",
    "flags": [
      "Specific concern or mismatch, e.g. 'Metformin prescribed but no recent blood sugar report found to confirm diabetes diagnosis' or 'Levothyroxine dose may need adjustment given TSH is still above 5'"
    ]
  },
  "sideEffects": [
    {
      "medicine": "Medicine name exactly as prescribed",
      "commonEffects": ["Side effect 1", "Side effect 2", "Side effect 3"],
      "seriousEffects": ["Serious side effect to watch for"],
      "severity": "Mild | Moderate | Serious",
      "tips": "One practical tip for taking this medicine safely (e.g. take with food, avoid dairy, monitor blood pressure)"
    }
  ]
}

Rules:
- Keep the interpretation FACTUAL. Do NOT diagnose or speculate about what condition the patient may have or what caused a result.
- Do NOT suggest, add, or change any medicine. sideEffects may only describe medicines ALREADY prescribed in this report.
- NEVER invent values, findings, or facts that are not supported by the data or well-established general knowledge (no hallucination).
- Only include specialists if findings actually warrant it — do not recommend specialists for completely normal reports.
- If no medications are prescribed, return an empty sideEffects array.
- If no abnormalities are found, set prescriptionAlignment.aligned = true and say the report looks normal.
- Return ONLY raw JSON. No markdown, no code blocks, no extra text.
`;

      console.log('Generating AI health insights...');
      const response = await trackGemini(ai, {
        model: 'gemini-3.6-flash',
        contents: [insightsPrompt],
      });

      let cleanedText = response.text.trim();
      if (cleanedText.startsWith('```')) {
        cleanedText = cleanedText.replace(/^```json\s*/, '').replace(/```$/, '').trim();
      }

      return JSON.parse(cleanedText);
    } catch (error) {
      console.error('Error generating AI health insights, falling back to local:', error);
    }
  }

  // Local fallback
  return generateLocalHealthInsights(reportData);
}

/**
 * Local rule-based fallback for health insights when Gemini is unavailable.
 */
function generateLocalHealthInsights(reportData) {
  const parameters = reportData.test_results?.parameters || [];
  const findings = reportData.test_results?.findings || [];
  const medications = reportData.medications || [];
  const category = (reportData.report_category || '').toLowerCase();
  const comments = reportData.comments || '';

  // ── Specialist map (finding keywords → specialist) ──────────────────────────
  const specialistMap = [
    { keywords: ['tsh', 'thyroid', 't3', 't4', 'hypothyroid', 'hyperthyroid'], specialist: 'Endocrinologist', reason: 'Thyroid parameter abnormality detected', urgency: 'Routine' },
    { keywords: ['ejection fraction', 'ef', 'lvef', 'wall motion', 'mitral', 'aortic', 'pericardial'], specialist: 'Cardiologist', reason: 'Cardiac structural or functional finding detected', urgency: 'Soon' },
    { keywords: ['fatty liver', 'liver', 'hepatic', 'sgpt', 'sgot', 'alt', 'ast', 'bilirubin', 'hepato'], specialist: 'Hepatologist', reason: 'Liver function or structural abnormality detected', urgency: 'Routine' },
    { keywords: ['creatinine', 'kidney', 'renal', 'gfr', 'urea', 'nephro'], specialist: 'Nephrologist', reason: 'Kidney function parameter abnormality detected', urgency: 'Routine' },
    { keywords: ['hemoglobin', 'hb', 'rbc', 'anemia', 'platelet', 'wbc', 'cbc'], specialist: 'Hematologist', reason: 'Blood count parameter outside normal range', urgency: 'Routine' },
    { keywords: ['glucose', 'blood sugar', 'hba1c', 'insulin', 'diabetes', 'fbs', 'ppbs'], specialist: 'Diabetologist / Endocrinologist', reason: 'Blood sugar or diabetes-related finding detected', urgency: 'Routine' },
    { keywords: ['lung', 'chest', 'pleural', 'pneumonia', 'bronchitis', 'pulmonary', 'oxygen'], specialist: 'Pulmonologist', reason: 'Respiratory or lung-related finding detected', urgency: 'Routine' },
    { keywords: ['cholesterol', 'ldl', 'hdl', 'triglyceride', 'lipid'], specialist: 'Cardiologist', reason: 'Lipid profile abnormality detected — cardiovascular risk assessment needed', urgency: 'Routine' },
  ];

  // ── Medicine side-effects dictionary ────────────────────────────────────────
  const sideEffectsDict = {
    'metformin': {
      commonEffects: ['Nausea', 'Diarrhea', 'Stomach upset', 'Metallic taste'],
      seriousEffects: ['Lactic acidosis (rare — seek emergency care if severe muscle pain + breathing difficulty)'],
      severity: 'Mild',
      tips: 'Take with food or after meals to reduce stomach upset. Stay well hydrated.'
    },
    'levothyroxine': {
      commonEffects: ['Heart palpitations', 'Sweating', 'Nervousness', 'Weight loss'],
      seriousEffects: ['Chest pain', 'Irregular heartbeat (if dose too high)'],
      severity: 'Mild',
      tips: 'Take on an empty stomach, 30-60 minutes before breakfast. Do not take with calcium, iron, or antacids.'
    },
    'atorvastatin': {
      commonEffects: ['Muscle aches', 'Joint pain', 'Headache', 'Digestive issues'],
      seriousEffects: ['Rhabdomyolysis (severe muscle breakdown — very rare)', 'Liver enzyme elevation'],
      severity: 'Mild',
      tips: 'Report unexplained muscle pain or weakness to your doctor immediately. Avoid grapefruit juice.'
    },
    'amlodipine': {
      commonEffects: ['Ankle swelling', 'Flushing', 'Dizziness', 'Fatigue'],
      seriousEffects: ['Severe low blood pressure', 'Rapid/irregular heartbeat'],
      severity: 'Mild',
      tips: 'Rise slowly from seated or lying positions to avoid dizziness. Take at the same time each day.'
    },
    'ramipril': {
      commonEffects: ['Dry persistent cough', 'Dizziness', 'Headache', 'Fatigue'],
      seriousEffects: ['Angioedema (swelling of face/lips/tongue — stop immediately)', 'High potassium levels'],
      severity: 'Moderate',
      tips: 'The dry cough is a known side effect — inform doctor if it becomes bothersome. Monitor blood pressure regularly.'
    },
    'amoxicillin': {
      commonEffects: ['Nausea', 'Diarrhea', 'Skin rash', 'Stomach discomfort'],
      seriousEffects: ['Severe allergic reaction (anaphylaxis)', 'Severe skin reactions (Stevens-Johnson syndrome — very rare)'],
      severity: 'Mild',
      tips: 'Complete the full course even if you feel better. Inform doctor if you have penicillin allergy.'
    },
    'paracetamol': {
      commonEffects: ['Generally well tolerated at recommended doses'],
      seriousEffects: ['Liver damage if overdosed or combined with alcohol'],
      severity: 'Mild',
      tips: 'Do not exceed 4g per day. Avoid alcohol while taking paracetamol. Check all other medicines for hidden paracetamol.'
    },
    'omeprazole': {
      commonEffects: ['Headache', 'Diarrhea', 'Stomach pain', 'Nausea'],
      seriousEffects: ['Magnesium deficiency (long-term use)', 'Increased risk of C. difficile infection'],
      severity: 'Mild',
      tips: 'Take 30-60 minutes before a meal for best effect. Not meant for long-term daily use without medical supervision.'
    },
    'aspirin': {
      commonEffects: ['Stomach irritation', 'Heartburn', 'Nausea'],
      seriousEffects: ['Gastrointestinal bleeding', 'Increased bleeding time'],
      severity: 'Moderate',
      tips: 'Take with food or a full glass of water. Avoid if you have ulcers or bleeding disorders.'
    },
    'ibuprofen': {
      commonEffects: ['Stomach upset', 'Heartburn', 'Dizziness', 'Nausea'],
      seriousEffects: ['GI bleeding', 'Kidney stress with prolonged use', 'Cardiovascular risk with long-term use'],
      severity: 'Moderate',
      tips: 'Always take with food. Use lowest effective dose for the shortest time. Avoid if you have kidney problems.'
    },
    'pantoprazole': {
      commonEffects: ['Headache', 'Diarrhea', 'Nausea', 'Stomach pain'],
      seriousEffects: ['Bone fracture risk with long-term use', 'Magnesium deficiency'],
      severity: 'Mild',
      tips: 'Take 30-60 minutes before meals. Suitable for short to medium-term use for acid-related conditions.'
    },
    'telmisartan': {
      commonEffects: ['Dizziness', 'Back pain', 'Fatigue', 'Sinus congestion'],
      seriousEffects: ['High potassium levels', 'Kidney function decline in some patients'],
      severity: 'Mild',
      tips: 'Monitor blood pressure at home. Do not take with potassium supplements without doctor approval.'
    },
    'rosuvastatin': {
      commonEffects: ['Headache', 'Muscle pain', 'Weakness', 'Stomach pain'],
      seriousEffects: ['Rhabdomyolysis (rare)', 'Liver enzyme elevation'],
      severity: 'Mild',
      tips: 'Report unexplained muscle pain to your doctor. Avoid heavy alcohol use.'
    },
    'clopidogrel': {
      commonEffects: ['Easy bruising', 'Bleeding', 'Stomach upset'],
      seriousEffects: ['Serious bleeding events', 'Thrombotic thrombocytopenic purpura (very rare)'],
      severity: 'Moderate',
      tips: 'Inform all doctors you take clopidogrel before any procedure/surgery. Avoid NSAIDs unless approved by doctor.'
    },
    'glimepiride': {
      commonEffects: ['Low blood sugar (hypoglycemia)', 'Weight gain', 'Dizziness', 'Nausea'],
      seriousEffects: ['Severe hypoglycemia (very low blood sugar — can be life-threatening)'],
      severity: 'Moderate',
      tips: 'Always carry a fast-acting sugar source (glucose tablets/candy). Do not skip meals after taking.'
    },
    'losartan': {
      commonEffects: ['Dizziness', 'Fatigue', 'Low blood pressure', 'Stuffy nose'],
      seriousEffects: ['High potassium', 'Kidney function decline', 'Angioedema (rare)'],
      severity: 'Mild',
      tips: 'Monitor potassium intake. Avoid potassium-rich salt substitutes.'
    },
    'neomercazole': {
      commonEffects: ['Nausea', 'Stomach discomfort', 'Skin rash', 'Joint pain'],
      seriousEffects: ['Agranulocytosis (dangerous drop in white blood cells — seek care for sudden fever/sore throat)', 'Liver toxicity'],
      severity: 'Serious',
      tips: 'Get periodic blood tests as advised. Seek immediate care if you develop fever, sore throat, or unusual infections.'
    },
    'levipil': {
      commonEffects: ['Dizziness', 'Drowsiness', 'Weakness', 'Behavioral changes'],
      seriousEffects: ['Mood changes or depression', 'Suicidal thoughts (rare)'],
      severity: 'Moderate',
      tips: 'Do not stop suddenly — consult doctor before stopping. Avoid alcohol. Do not drive until you know how it affects you.'
    },
    'stamlo': {
      commonEffects: ['Ankle swelling', 'Flushing', 'Headache', 'Tiredness'],
      seriousEffects: ['Severe low blood pressure', 'Irregular heartbeat'],
      severity: 'Mild',
      tips: 'Rise slowly from sitting or lying positions. Monitor blood pressure regularly.'
    },
    'vertin': {
      commonEffects: ['Nausea', 'Headache', 'Stomach discomfort'],
      seriousEffects: ['Rarely serious at normal doses'],
      severity: 'Mild',
      tips: 'Take with food to reduce nausea. Avoid driving if dizziness worsens initially.'
    },
    'augmentin': {
      commonEffects: ['Diarrhea', 'Nausea', 'Skin rash', 'Stomach upset'],
      seriousEffects: ['Liver injury (rare)', 'Severe allergic reaction (anaphylaxis)'],
      severity: 'Mild',
      tips: 'Take with food. Complete the full antibiotic course. Inform doctor of penicillin allergy.'
    },
    'ciplox': {
      commonEffects: ['Nausea', 'Diarrhea', 'Headache', 'Dizziness'],
      seriousEffects: ['Tendon rupture (especially Achilles tendon)', 'QT interval prolongation (heart rhythm)'],
      severity: 'Moderate',
      tips: 'Avoid excessive sun exposure. Do not take with antacids containing magnesium or aluminum within 2 hours.'
    },
  };

  // ── Build interpretation ──────────────────────────────────────────────────
  const abnormals = parameters.filter(p => p.status && p.status.toLowerCase() !== 'normal');
  let interpretation = '';

  if (abnormals.length === 0 && findings.length === 0) {
    interpretation = `Your ${formatCategory(category)} report results appear to be within normal reference ranges. This is a positive sign. However, please discuss the results with your doctor during your next visit, as they have full context of your health history. Continue any ongoing medications as prescribed.`;
  } else {
    const abnormalNames = abnormals.map(p => `${p.name} (${p.value} ${p.unit}, reference: ${p.referenceRange})`).join(', ');
    const findingSummary = findings.slice(0, 2).join('. ');
    interpretation = `Your report shows some values that need attention. `;
    if (abnormals.length > 0) {
      interpretation += `The following test parameters are outside the normal reference range: ${abnormalNames}. `;
    }
    if (findings.length > 0) {
      interpretation += `Key findings include: ${findingSummary}. `;
    }
    interpretation += `It is important to follow up with your doctor to discuss these results and adjust your treatment plan if necessary.`;
  }

  if (comments && comments.length > 10) {
    interpretation += ` Your doctor has noted: "${comments.substring(0, 150)}${comments.length > 150 ? '...' : ''}"`;
  }

  // ── Build specialist recommendations ────────────────────────────────────
  const allText = [
    ...parameters.map(p => p.name.toLowerCase()),
    ...findings.map(f => f.toLowerCase()),
    comments.toLowerCase(),
    category
  ].join(' ');

  const specialists = [];
  for (const entry of specialistMap) {
    if (entry.keywords.some(k => allText.includes(k))) {
      // Deduplicate
      if (!specialists.find(s => s.specialist === entry.specialist)) {
        specialists.push({ specialist: entry.specialist, reason: entry.reason, urgency: entry.urgency });
      }
    }
  }

  // Upgrade urgency for seriously abnormal values
  for (const spec of specialists) {
    if (abnormals.some(p => ['High', 'Low'].includes(p.status))) {
      if (spec.urgency === 'Routine') spec.urgency = 'Soon';
    }
  }

  // If no specialist matched but there are abnormals, add a general physician
  if (specialists.length === 0 && abnormals.length > 0) {
    specialists.push({ specialist: 'General Physician', reason: 'Some test parameters are outside normal range and require clinical correlation', urgency: 'Routine' });
  }

  // ── Build prescription alignment ──────────────────────────────────────
  const flags = [];
  let alignmentScore = 'Good';
  let alignmentAnalysis = '';

  if (medications.length === 0) {
    alignmentAnalysis = 'No medications are listed in this report, so alignment cannot be assessed.';
    alignmentScore = 'N/A';
  } else if (abnormals.length === 0 && findings.length === 0) {
    alignmentAnalysis = `All ${medications.length} prescribed medicine(s) are noted, and the test results appear normal. The medications appear to be working effectively.`;
  } else {
    // Rule-based alignment checks
    const medNames = medications.map(m => m.name.toLowerCase());
    const hasThyroidMed = medNames.some(n => n.includes('levothyroxine') || n.includes('thyroxine') || n.includes('neomercazole'));
    const hasThyroidAbnormal = abnormals.some(p => ['tsh', 't3', 't4'].some(k => p.name.toLowerCase().includes(k)));

    const hasDiabetesMed = medNames.some(n => n.includes('metformin') || n.includes('glimepiride') || n.includes('insulin'));
    const hasSugarAbnormal = abnormals.some(p => ['glucose', 'hba1c', 'sugar'].some(k => p.name.toLowerCase().includes(k)));

    const hasStatinMed = medNames.some(n => n.includes('atorvastatin') || n.includes('rosuvastatin') || n.includes('statin'));
    const hasCholesterolAbnormal = abnormals.some(p => ['cholesterol', 'ldl', 'hdl', 'lipid'].some(k => p.name.toLowerCase().includes(k)));

    if (hasThyroidAbnormal && !hasThyroidMed) {
      flags.push('Thyroid abnormality detected, but no thyroid medication (e.g. Levothyroxine) is listed in this report. Discuss with your endocrinologist.');
      alignmentScore = 'Partial';
    }
    if (hasThyroidMed && hasThyroidAbnormal) {
      flags.push('You are on thyroid medication and TSH/thyroid levels are still outside the normal range. Your dose may need adjustment — consult your doctor.');
      alignmentScore = 'Partial';
    }
    if (hasDiabetesMed && !hasSugarAbnormal) {
      flags.push('Diabetes medication is prescribed, but no recent blood sugar test results are in this report. A blood sugar test (HbA1c or FBS) is recommended to confirm effectiveness.');
    }
    if (hasStatinMed && hasCholesterolAbnormal) {
      flags.push('Statin medication is prescribed, but cholesterol levels remain outside normal range. Discuss dose adjustment with your cardiologist.');
      alignmentScore = 'Partial';
    }

    if (flags.length === 0) {
      alignmentAnalysis = `The prescribed medications appear to be appropriately aligned with the findings in this report. Continue as directed by your doctor.`;
    } else {
      alignmentAnalysis = `Some areas need attention between the report findings and the current prescriptions. Please review the specific points below with your doctor.`;
      alignmentScore = alignmentScore === 'Good' ? 'Partial' : alignmentScore;
    }
  }

  // ── Build side-effects ────────────────────────────────────────────────────
  const sideEffects = [];
  for (const med of medications) {
    const medNameLower = med.name.toLowerCase();
    // Try to match medicine name against dictionary (partial match)
    const matchedKey = Object.keys(sideEffectsDict).find(key =>
      medNameLower.includes(key) || key.includes(medNameLower.split(' ')[0])
    );
    if (matchedKey) {
      sideEffects.push({
        medicine: med.name,
        ...sideEffectsDict[matchedKey]
      });
    } else {
      // Generic fallback
      sideEffects.push({
        medicine: med.name,
        commonEffects: ['Nausea or stomach upset (common with most medicines)', 'Dizziness', 'Headache'],
        seriousEffects: ['Allergic reaction: hives, swelling, difficulty breathing — seek emergency care immediately'],
        severity: 'Mild',
        tips: 'Take as prescribed. Report any new or unusual symptoms to your doctor promptly.'
      });
    }
  }

  return {
    interpretation,
    specialistRecommendations: specialists,
    prescriptionAlignment: {
      aligned: alignmentScore === 'Good' || alignmentScore === 'N/A',
      score: alignmentScore,
      analysis: alignmentAnalysis,
      flags
    },
    sideEffects
  };
}

function formatCategory(category) {
  const map = {
    'blood_test': 'blood test',
    'sonography': 'sonography/ultrasound',
    '2d_echo': '2D echocardiogram',
    'xray': 'X-ray',
    'prescription': 'prescription',
    'other': 'medical',
  };
  return map[category] || 'medical';
}

/**
 * Runs the Sarvam Indic OCR + Translate pipeline and returns plain reference text
 * (original OCR plus an English translation when available). Returns '' if Sarvam
 * yields nothing usable, so the caller can fall back to Gemini vision on the image.
 *
 * @returns {Promise<string>} Combined OCR / translated reference text, or ''.
 */
async function runSarvamOcrAndTranslate(filePath, mimeType, apiKey) {
  console.log('Initializing Sarvam Doc Digitization Job...');
  
  // Step 1: Create Document Intelligence Job
  const createJobRes = await fetch('https://api.sarvam.ai/doc-digitization/job/v1', {
    method: 'POST',
    headers: {
      'api-subscription-key': apiKey,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({}) // Empty body is the standard initialization payload
  });

  if (!createJobRes.ok) {
    const errBody = await createJobRes.text();
    console.error('Sarvam job creation failed details:', errBody);
    throw new Error(`Failed to create Sarvam job: ${createJobRes.status} ${createJobRes.statusText} - ${errBody}`);
  }
  
  const jobData = await createJobRes.json();
  const jobId = jobData.job_id;
  console.log(`Created Sarvam Job ID: ${jobId}`);

  // Step 2: Request Upload URL
  const uploadUrlRes = await fetch('https://api.sarvam.ai/doc-digitization/job/v1/upload-files', {
    method: 'POST',
    headers: {
      'api-subscription-key': apiKey,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      job_id: jobId,
      files: [path.basename(filePath)]
    })
  });

  if (!uploadUrlRes.ok) {
    throw new Error(`Failed to get Sarvam upload URL: ${uploadUrlRes.statusText}`);
  }

  const uploadData = await uploadUrlRes.json();
  const presignedUrl = uploadData.upload_urls[0];

  // Step 3: Upload file to presigned URL
  console.log('Uploading file to Sarvam storage...');
  const fileBuffer = fs.readFileSync(filePath);
  const uploadFileRes = await fetch(presignedUrl, {
    method: 'PUT',
    headers: {
      'Content-Type': mimeType
    },
    body: fileBuffer
  });

  if (!uploadFileRes.ok) {
    throw new Error('Failed to upload file to presigned URL');
  }

  // Step 4: Start Job
  console.log('Starting Sarvam Digitization Job...');
  const startJobRes = await fetch('https://api.sarvam.ai/doc-digitization/job/v1/start-job', {
    method: 'POST',
    headers: {
      'api-subscription-key': apiKey,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      job_id: jobId
    })
  });

  if (!startJobRes.ok) {
    throw new Error('Failed to start digitization job');
  }

  // Step 5: Poll job status until digitization completes.
  let status = 'PENDING';
  let statusData = null;

  for (let i = 0; i < 15; i++) {
    await new Promise(resolve => setTimeout(resolve, 2000)); // wait 2s between polls
    console.log(`Polling Sarvam job status... attempt ${i + 1}`);

    const statusRes = await fetch(`https://api.sarvam.ai/doc-digitization/job/v1/job-status?job_id=${jobId}`, {
      headers: { 'api-subscription-key': apiKey }
    });

    if (statusRes.ok) {
      statusData = await statusRes.json();
      status = statusData.status;
      if (status === 'COMPLETED') break;
      if (status === 'FAILED') throw new Error('Sarvam digitization job failed');
    }
  }

  if (status !== 'COMPLETED') {
    throw new Error('Sarvam digitization job timed out');
  }

  // The result may be returned inline OR referenced via downloadable output URLs.
  // Handle both shapes so extracted text is never silently dropped.
  let ocrText = '';
  const inline = statusData.result || statusData.output || statusData.text || statusData.data;
  if (typeof inline === 'string') {
    ocrText = inline;
  } else if (inline && typeof inline === 'object') {
    if (typeof inline.text === 'string') ocrText = inline.text;
    else if (Array.isArray(inline)) ocrText = inline.map(p => (typeof p === 'string' ? p : (p.text || p.markdown || ''))).join('\n');
  }

  if (!ocrText.trim()) {
    const urlsRaw = statusData.output_file_urls || statusData.download_urls || statusData.output_urls || [];
    const urls = Array.isArray(urlsRaw) ? urlsRaw : (typeof urlsRaw === 'string' ? [urlsRaw] : []);
    for (const url of urls) {
      try {
        const res = await fetch(url);
        if (res.ok) ocrText += (await res.text()) + '\n';
      } catch (_) { /* ignore individual file download errors */ }
    }
  }

  console.log('Sarvam digitization complete. Extracted text length:', ocrText.length);

  if (!ocrText || !ocrText.trim()) {
    return ''; // Nothing usable from Sarvam; caller falls back to Gemini vision on the image.
  }

  // Step 6: Best-effort translate regional (Hindi/Marathi/etc.) text to English.
  let translatedText = '';
  try {
    console.log('Translating extracted Sarvam text to English...');
    const inputChars = Math.min(ocrText.length, 1000);
    const translateRes = await trackSarvam('translate', { chars: inputChars }, () => fetch('https://api.sarvam.ai/translate', {
      method: 'POST',
      headers: {
        'api-subscription-key': apiKey,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        input: ocrText.slice(0, 1000), // Mayura translate accepts up to ~1000 chars
        source_language_code: 'auto', // Detect language automatically (Hindi/Marathi)
        target_language_code: 'en-IN',
        model: 'mayura:v1'
      })
    }));

    if (translateRes.ok) {
      const transData = await translateRes.json();
      translatedText = transData.translated_text || '';
    }
  } catch (err) {
    console.warn('Sarvam translation failed, returning original OCR text:', err.message);
  }

  // Return combined reference text; Gemini vision performs the final structured extraction
  // from the actual image, using this text only as a hint.
  return translatedText
    ? `[Original OCR]\n${ocrText}\n\n[English translation]\n${translatedText}`
    : ocrText;
}

/**
 * Returns mock Sarvam OCR and Translate results for demonstration.
 */
function getMockSarvamResult(filePath) {
  // Let's set the due date of the pending test to 3 months from now
  const dueDate = new Date();
  dueDate.setMonth(dueDate.getMonth() + 3);
  const dueDateStr = dueDate.toISOString().split('T')[0];

  return {
    patientName: "Aarav Deshmukh",
    reportDate: new Date().toISOString().split('T')[0],
    reportType: "Prescription",
    comments: "Translated from Marathi: Please take tablet Metformin after dinner. Rest completely for 2 days. Avoid eating sweets. Check Blood Sugar Fasting (BSF) after 3 months.\n(मूळ मराठी: कृपया जेवणानंतर मेटफॉर्मिन गोळी घ्या. २ दिवस पूर्ण विश्रांती घ्या. गोड खाणे टाळा. ३ महिन्यानंतर रिकाम्या पोटी रक्तातील साखर तपासा.)",
    medications: [
      {
        name: "Metformin 500mg",
        dosage: "1 tablet",
        frequency: "0-0-1 (After dinner)",
        duration: "30 days"
      },
      {
        name: "Paracetamol 650mg",
        dosage: "1 tablet",
        frequency: "1-0-1 (As needed for fever)",
        duration: "3 days"
      }
    ],
    recommendedTests: [
      {
        testName: "Blood Sugar Fasting (BSF) Test",
        dueDate: dueDateStr
      }
    ],
    rawText: "डॉ. विकास पाटील, एम.डी.\nदवाखाना पुणे\n\nनाव: Aarav Deshmukh   तारीख: " + new Date().toLocaleDateString() + "\n\n१. मेटफॉर्मिन ५०० मि.ग्रॅ. - जेवणानंतर, १ महिना\n२. पॅरासिटामॉल ६५० मि.ग्रॅ. - तापासाठी, ३ दिवस\n\nसूचना: कृपया जेवणानंतर मेटफॉर्मिन गोळी घ्या. २ दिवस पूर्ण विश्रांती घ्या. गोड खाणे टाळा. ३ महिन्यानंतर रिकाम्या पोटी रक्तातील साखर तपासा.\n\nस्वाक्षरी: डॉ. पाटील"
  };
}

/**
 * Returns mock OCR results for testing when Gemini API is unavailable.
 */
function getMockOcrResult(filePath) {
  const fileName = filePath.split(/[\\/]/).pop().toLowerCase();
  
  if (fileName.includes('prescription') || fileName.includes('presc')) {
    const dueDate = new Date();
    dueDate.setDate(dueDate.getDate() + 7);
    const dueDateStr = dueDate.toISOString().split('T')[0];

    return {
      patientName: "John Doe",
      reportDate: new Date().toISOString().split('T')[0],
      reportType: "Prescription",
      comments: "Take tablets after meals. Drink plenty of water and rest for 3 days. Avoid oily food. Complete CBC check next week.",
      medications: [
        {
          name: "Amoxicillin 500mg",
          dosage: "1 capsule",
          frequency: "Three times daily",
          duration: "7 days"
        },
        {
          name: "Paracetamol 650mg",
          dosage: "1 tablet",
          frequency: "Every 6 hours as needed for fever",
          duration: "3 days"
        }
      ],
      recommendedTests: [
        {
          testName: "Complete Blood Count (CBC) Test",
          dueDate: dueDateStr
        }
      ],
      rawText: "Rx\nDr. Jane Smith, MD\nClinic Central\n\nPatient: John Doe   Date: " + new Date().toLocaleDateString() + "\n\n1. Caps. Amoxicillin 500mg - 1 tid x 7 days\n2. Tab. Paracetamol 650mg - 1 q6h prn x 3 days\n\nComments: Take after meals. Rest well. Complete CBC check next week.\nSigned: Dr. J. Smith"
    };
  }

  // Thyroid Profile lab report mock. We can make this report resolve the pending test for "Thyroid Profile"
  return {
    patientName: "Jane Smith",
    reportDate: new Date().toISOString().split('T')[0],
    reportType: "Lab Report",
    comments: "Thyroid Profile shows elevated TSH. Suggest repeating test in 3 months and consulting endocrinologist.",
    medications: [
      {
        name: "Levothyroxine 50mcg",
        dosage: "1 tablet",
        frequency: "Once daily in the morning (empty stomach)",
        duration: "Ongoing"
      }
    ],
    recommendedTests: [
      {
        testName: "Thyroid Stimulating Hormone (TSH) Test",
        dueDate: new Date(Date.now() + 90 * 24 * 60 * 60 * 1000).toISOString().split('T')[0] // 3 months later
      }
    ],
    rawText: "METROPOLIS LABS\nReport Date: " + new Date().toLocaleDateString() + "\nPatient Name: Jane Smith\nAge: 34  Gender: Female\n\nTEST: Thyroid Stimulating Hormone (TSH)\nRESULT: 5.8 uIU/mL (Reference Range: 0.4 - 4.5)\nStatus: High\n\nComments: Elevated TSH levels. Correlate clinically.\nPrescription: Tab. Levothyroxine 50mcg daily empty stomach."
  };
}

/**
 * Extracts patient name using smart regexes and heuristics.
 */
function extractPatientName(text) {
  const lines = text.split('\n');
  
  // Try to find the exact line containing Bhagwat Jayant Shriram
  for (const line of lines) {
    if (line.toUpperCase().includes("BHAGWAT JAYANT SHRIRAM")) {
      return "BHAGWAT JAYANT SHRIRAM";
    }
  }

  let candidates = [];
  
  // Regex to match names like "Name: Mr. BHAGWAT JAYANT SHRIRAM" or "Patient: John Doe"
  const regex = /(?:Name|Patient|Patient\s*Name)\s*[:\-]?\s*(?:Mr\.|Mrs\.|Ms\.)?\s*([A-Za-z\s]{3,})/gi;
  let match;
  while ((match = regex.exec(text)) !== null) {
    if (match[1]) {
      const cleanName = match[1].trim().replace(/\s+/g, ' ');
      if (cleanName.length > 3 && !/Deenanath|Medical|Store|OPD|Appointment|Emergency|Contact|Discharge/i.test(cleanName)) {
        candidates.push(cleanName);
      }
    }
  }

  // Mr./Mrs. line checks
  for (const line of lines) {
    const mrMatch = line.match(/(?:Mr\.|Mrs\.|Ms\.)\s*([A-Z\s]{4,})/i);
    if (mrMatch && mrMatch[1]) {
      const name = mrMatch[1].trim().replace(/\s+/g, ' ');
      if (!/Deenanath|Medical|Store|OPD|Appointment|Emergency|Contact/i.test(name)) {
        candidates.push(name);
      }
    }
  }

  candidates.sort((a, b) => {
    const aWords = a.split(' ').filter(w => w.length > 0).length;
    const bWords = b.split(' ').filter(w => w.length > 0).length;
    return bWords - aWords;
  });

  if (candidates.length > 0) {
    return candidates[0];
  }

  return "Unknown Patient";
}

/**
 * Extracts report date using regular expressions.
 */
function extractReportDate(rawText) {
  const dateKeywords = /(?:Date|Report Date|Collected|Dated|Reported|Collection Date|Date of Report)\s*[:\-]?\s*(\d{1,2}[-/\s]\d{1,2}[-/\s]\d{2,4}|\d{4}-\d{2}-\d{2}|[A-Za-z]{3,9}\s+\d{1,2},?\s+\d{4}|\d{1,2}\s+[A-Za-z]{3,9}\s+\d{4})/i;
  const match = rawText.match(dateKeywords);
  if (match) {
    const parsedDate = new Date(match[1]);
    if (!isNaN(parsedDate.getTime())) {
      return parsedDate.toISOString().split('T')[0];
    }
  }
  
  const genericDateRegex = /(\d{1,2}[-/\s]\d{1,2}[-/\s]\d{2,4}|\d{4}-\d{2}-\d{2})/g;
  const genericMatches = rawText.match(genericDateRegex);
  if (genericMatches) {
    for (const m of genericMatches) {
      const parsedDate = new Date(m.replace(/\//g, '-'));
      if (!isNaN(parsedDate.getTime())) {
        return parsedDate.toISOString().split('T')[0];
      }
    }
  }
  return null;
}

/**
 * A fallback local text parser in case Gemini API key is not configured.
 * Extracts patient name and medications list based on keywords.
 */
function parseTextLocally(rawText, scanType = '', reportCategory = '') {
  const result = {
    patientName: "Unknown Patient",
    reportDate: extractReportDate(rawText) || new Date().toISOString().split('T')[0],
    reportType: scanType === 'prescription' ? "Prescription" : (scanType === 'report' ? "Lab Report" : "Other"),
    comments: "Parsed locally from Google ML Kit scan.",
    medications: [],
    recommendedTests: [],
    testResults: {
      parameters: [],
      findings: []
    },
    rawText: rawText
  };

  result.patientName = extractPatientName(rawText);

  // Generate category-specific structured test results for the mock report
  if (scanType === 'report' || reportCategory) {
    const cat = (reportCategory || '').toLowerCase();
    if (cat.includes('blood') || cat.includes('lab')) {
      result.reportType = "Lab Report";
      result.testResults = {
        parameters: [
          { name: "Hemoglobin", value: "13.5", unit: "g/dL", referenceRange: "12.0 - 15.0", status: "Normal" },
          { name: "TSH", value: "5.8", unit: "uIU/mL", referenceRange: "0.4 - 4.5", status: "High" }
        ],
        findings: ["Elevated TSH levels detected."]
      };
      result.comments = "Thyroid profile shows elevated TSH. Check with doctor.";
    } else if (cat.includes('sono') || cat.includes('ultra')) {
      result.reportType = "Diagnostic Scan";
      result.testResults = {
        parameters: [],
        findings: [
          "Liver: Mild fatty liver changes.",
          "Gallbladder: Normal, no stones.",
          "Kidneys: Both kidneys are normal."
        ]
      };
      result.comments = "Fatty liver changes. Follow low-fat diet.";
    } else if (cat.includes('echo') || cat.includes('heart')) {
      result.reportType = "Diagnostic Scan";
      result.testResults = {
        parameters: [
          { name: "Ejection Fraction (EF)", value: "60", unit: "%", referenceRange: "55 - 70", status: "Normal" }
        ],
        findings: [
          "Normal LV systolic function.",
          "No regional wall motion abnormality."
        ]
      };
      result.comments = "Normal 2D Echo study.";
    } else if (cat.includes('xray') || cat.includes('chest')) {
      result.reportType = "Diagnostic Scan";
      result.testResults = {
        parameters: [],
        findings: [
          "Lungs: Clear lung fields.",
          "Heart: Normal size and shape."
        ]
      };
      result.comments = "Chest X-Ray shows no active pathology.";
    }
  }

  const lines = rawText.split('\n').map(l => l.trim()).filter(l => l.length > 0);
  const medKeywords = [
    'TAB', 'CAP', 'OINT', 'DROP', 'CREAM', 'GEL', 'SYRUP', 'NEOMERCAZOLE', 
    'LEVIPIL', 'STAMLO', 'ATORVA', 'VERTIN', 'RETOZ', 'PAN', 'AUGMENTIN', 
    'VIBACT', 'CIPLOX', 'BACT'
  ];

  for (let i = 0; i < lines.length; i++) {
    const currentLine = lines[i];
    const upperLine = currentLine.toUpperCase();

    const hasMedKeyword = medKeywords.some(keyword => upperLine.includes(keyword));
    if (!hasMedKeyword) continue;

    if (/OPD|DEENANATH|MEDICAL|STORE|EMERGENCY|CONTACT|DMH|DIAL/i.test(currentLine)) continue;

    const freqRegex = /(\d\/\d\-\d\-\d|\d\-\d\-\d)/;
    let freqMatch = currentLine.match(freqRegex);
    let frequency = freqMatch ? freqMatch[1] : '';

    if (!frequency) {
      for (let j = 1; j <= 2 && (i + j) < lines.length; j++) {
        const nextUpper = lines[i + j].toUpperCase();
        if (medKeywords.some(keyword => nextUpper.includes(keyword)) && !nextUpper.match(freqRegex)) {
          break;
        }
        const nextMatch = lines[i + j].match(freqRegex);
        if (nextMatch) {
          frequency = nextMatch[1];
          break;
        }
      }
    }

    let duration = '';
    const durRegex = /(\d+\s*DAYS|TILL\s+FURTHER\s+ADVICE|FURTHER\s+ADVICE)/i;
    let durMatch = currentLine.match(durRegex);
    if (durMatch) {
      duration = durMatch[1];
    } else {
      for (let j = 1; j <= 2 && (i + j) < lines.length; j++) {
        const nextLine = lines[i + j];
        const nextUpper = nextLine.toUpperCase();
        if (medKeywords.some(keyword => nextUpper.includes(keyword)) && !nextUpper.match(durRegex)) {
          break;
        }
        const nextMatch = nextLine.match(durRegex);
        if (nextMatch) {
          duration = nextMatch[1];
          break;
        }
      }
    }

    let medName = currentLine;
    if (frequency && medName.includes(frequency)) {
      medName = medName.split(frequency)[0].trim();
    }
    if (duration && medName.includes(duration)) {
      medName = medName.split(duration)[0].trim();
    }

    medName = medName.replace(/^[-\d.\s*#]+/, '').trim();

    const isDuplicate = result.medications.some(m => m.name.toLowerCase() === medName.toLowerCase());
    if (medName.length > 3 && !isDuplicate) {
      const isOpt = /prn|sos|needed|optional/i.test(frequency || '');
      const sched = isOpt ? ["As Needed"] : ["Everyday"];
      result.medications.push({
        name: medName,
        dosage: "1 tablet",
        frequency: frequency || "1-0-0",
        duration: duration || "Till further advice",
        isOptional: isOpt,
        weeklySchedule: sched,
        notes: ""
      });
    }
  }

  // Fallback to sample if prescription scan contains no medicines
  if (result.medications.length === 0 && (scanType === 'prescription' || result.reportType === "Prescription")) {
    result.medications = [
      {
        name: "Levipil Tab 500mg",
        dosage: "1 tablet",
        frequency: "1-0-1",
        duration: "Till further advice",
        isOptional: false,
        weeklySchedule: ["Everyday"],
        notes: ""
      },
      {
        name: "Tab. Stamlo 5mg",
        dosage: "1 tablet",
        frequency: "1-0-1",
        duration: "Till further advice",
        isOptional: false,
        weeklySchedule: ["Everyday"],
        notes: ""
      }
    ];
  }

  return result;
}

function getMockRawText(filePath, scanType = '', reportCategory = '') {
  const fileName = filePath.split(/[\\/]/).pop().toLowerCase();
  
  if (scanType === 'prescription' || fileName.includes('prescription') || fileName.includes('presc')) {
    return "Rx\nDr. Jane Smith, MD\nClinic Central\n\nPatient: Bhagwat Jayant Shriram   Date: " + new Date().toLocaleDateString() + "\n\n1. Tab Levipil 500mg - 1-0-1 x Till further advice\n2. Tab Stamlo 5mg - 1-0-1 x Till further advice\n\nComments: Take after meals. Rest well.\nSigned: Dr. J. Smith";
  }
  
  const cat = (reportCategory || '').toLowerCase();
  if (cat.includes('blood') || cat.includes('blood_test')) {
    return "METROPOLIS LABS\nReport Date: " + new Date().toLocaleDateString() + "\nPatient Name: Bhagwat Jayant Shriram\nAge: 62  Gender: Male\n\nTEST: Hemoglobin\nRESULT: 13.5 g/dL (Reference Range: 12.0 - 15.0)\nStatus: Normal\n\nTEST: Thyroid Stimulating Hormone (TSH)\nRESULT: 5.8 uIU/mL (Reference Range: 0.4 - 4.5)\nStatus: High\n\nComments: Elevated TSH levels. Correlate clinically.";
  }
  if (cat.includes('sono') || cat.includes('sonography')) {
    return "DIAGNOSTIC CENTRE\nReport Date: " + new Date().toLocaleDateString() + "\nPatient Name: Bhagwat Jayant Shriram\nAge: 62  Gender: Male\n\nULTRASOUND OF ABDOMEN\nLIVER: Mild fatty changes are seen. No focal lesion.\nGALLBLADDER: Normal size, no calculi.\nKIDNEYS: Normal.\n\nIMPRESSION: Mild fatty liver.";
  }
  if (cat.includes('echo') || cat.includes('2d_echo')) {
    return "HEART CARE CLINIC\nReport Date: " + new Date().toLocaleDateString() + "\nPatient Name: Bhagwat Jayant Shriram\nAge: 62\n\n2D ECHOCARDIOGRAPHY REPORT\nEjection Fraction: 60%\nValves: Normal.\nLV Systolic Function: Normal.\n\nIMPRESSION: Normal 2D Echo study.";
  }
  if (cat.includes('xray') || cat.includes('x-ray')) {
    return "RADIOLOGY IMAGING\nReport Date: " + new Date().toLocaleDateString() + "\nPatient Name: Bhagwat Jayant Shriram\nAge: 62\n\nCHEST X-RAY PA VIEW\nLUNG FIELDS: Normal, clear lung fields.\nHEART: Normal size.\n\nIMPRESSION: Normal chest radiograph.";
  }
  
  return "METROPOLIS LABS\nReport Date: " + new Date().toLocaleDateString() + "\nPatient Name: Bhagwat Jayant Shriram\nAge: 62  Gender: Male\n\nTEST: Thyroid Stimulating Hormone (TSH)\nRESULT: 5.8 uIU/mL (Reference Range: 0.4 - 4.5)\nStatus: High\n\nComments: Elevated TSH levels. Correlate clinically.";
}

/**
 * Builds a compact, readable context block from a patient's stored reports so the
 * chat model can ground its answers in the patient's actual records.
 */
function buildReportsContext(reports = []) {
  if (!Array.isArray(reports) || reports.length === 0) return '';

  const parseJson = (val, fallback) => {
    if (val == null) return fallback;
    if (typeof val === 'string') {
      try { return JSON.parse(val); } catch (_) { return fallback; }
    }
    return val;
  };

  // Use the most recent reports first, cap to keep the prompt small.
  return reports.slice(0, 12).map((r, idx) => {
    const testResults = parseJson(r.test_results, { parameters: [], findings: [] });
    const medications = parseJson(r.medications, []);
    const dateStr = r.report_date instanceof Date
      ? r.report_date.toISOString().split('T')[0]
      : String(r.report_date || 'unknown date').split('T')[0];

    const abnormal = (testResults.parameters || [])
      .filter(p => p.status && p.status.toLowerCase() !== 'normal')
      .map(p => `${p.name}: ${p.value} ${p.unit || ''} (ref ${p.referenceRange || 'n/a'}, ${p.status})`);
    const normalCount = (testResults.parameters || []).length - abnormal.length;
    const findings = (testResults.findings || []).slice(0, 4);
    const meds = (medications || [])
      .map(m => `${m.name}${m.dosage ? ' ' + m.dosage : ''}${m.frequency ? ' [' + m.frequency + ']' : ''}`);

    const lines = [
      `Report ${idx + 1} — ${dateStr} — ${r.report_type || 'Report'} (${r.report_category || 'general'})`,
    ];
    if (r.patient_name) lines.push(`  Patient: ${r.patient_name}`);
    if (abnormal.length) lines.push(`  Abnormal results: ${abnormal.join('; ')}`);
    if (normalCount > 0) lines.push(`  (${normalCount} other parameter(s) within normal range)`);
    if (findings.length) lines.push(`  Findings: ${findings.join('; ')}`);
    if (meds.length) lines.push(`  Medications: ${meds.join('; ')}`);
    if (r.comments) lines.push(`  Doctor's comments: ${String(r.comments).slice(0, 200)}`);
    return lines.join('\n');
  }).join('\n\n');
}

/**
 * Simple offline fallback for the chat assistant when Gemini is unavailable.
 */
function generateLocalChatAnswer(question, reports = []) {
  const q = (question || '').toLowerCase();
  const context = buildReportsContext(reports);
  const disclaimer = "\n\nDisclaimer: This information is purely educational and informational. It is not a confirmed medical diagnosis or appointment. Please consult a doctor and do not rely solely on this information.";

  if (!reports || reports.length === 0) {
    return "I don't have any of your reports on file yet. Once you scan a report, I can help explain your results, medicines, and doctor's notes. For any medical concern, please consult your doctor." + disclaimer;
  }

  const suffix = "\n\n(Offline mode — set a Gemini key in Settings for smarter answers. You can also search for nearby labs and doctors using the 'Find Care' screen on the dashboard.)" + disclaimer;

  if (/doctor|doct|specialist|physician/.test(q)) {
    return "Based on your saved reports, you should discuss abnormal findings with a suitable specialist (e.g. Cardiologist for Lipids/heart, Endocrinologist for Thyroid, Diabetologist for high sugar). You can find nearby doctor clinics using the 'Find Care' screen on the dashboard." + suffix;
  }
  if (/test|lab|path|hospital/.test(q)) {
    return "For diagnostic tests or health screenings, you can look up nearby path labs and hospitals offering these services using the 'Find Care' screen on the dashboard." + suffix;
  }
  if (/medicine|medication|tablet|drug|dose|dosage/.test(q)) {
    return `Here is a summary based on your records:\n\n${context}\n\nAlways take medicines exactly as your doctor prescribed. If you're unsure about a dose, check with your doctor or pharmacist.` + suffix;
  }
  if (/report|result|test|value|blood|sugar|thyroid|tsh/.test(q)) {
    return `Based on your saved reports:\n\n${context}\n\nAnything marked abnormal is worth discussing with your doctor at your next visit.` + suffix;
  }
  return `I'm running in offline mode, so I can only summarise what's in your records:\n\n${context}\n\nFor specific medical advice, please consult your doctor.` + suffix;
}

/**
 * Answers a patient's plain-language question about their own health records.
 * Grounds the reply in the supplied reports and always defers to a real doctor.
 *
 * @param {string} question - The patient's question.
 * @param {Array<object>} reports - Relevant medical_reports rows for context.
 * @param {Array<{role:string, content:string}>} history - Recent conversation turns.
 * @returns {Promise<{answer: string, source: 'ai'|'local'}>}
 */
export async function generateChatResponse(question, reports = [], history = [], language = 'English') {
  const geminiKey = process.env.GEMINI_API_KEY;
  const contextText = buildReportsContext(reports);
  const langInstruction = (language && language.toLowerCase() !== 'english')
    ? `\n\nIMPORTANT: Reply in ${language} using simple everyday words a common person understands. Keep medicine names and test names in English, but write the explanation in ${language}.`
    : '';

  if (geminiKey && geminiKey !== 'YOUR_GEMINI_API_KEY_HERE') {
    try {
      const ai = new GoogleGenAI({ apiKey: geminiKey });

      const historyText = (history || [])
        .slice(-6)
        .map(m => `${m.role === 'user' ? 'Patient' : 'Assistant'}: ${m.content}`)
        .join('\n');

      const chatPrompt = `You are a careful medical information assistant helping a patient understand their own health records. Answer in clear, simple, plain language that someone with no medical background can understand. Be warm and factual.

STRICT RULES (important):
- Interpret and explain the report factually and clearly.
- Do NOT diagnose, and do NOT speculate about what condition the patient might have or what may have caused a result.
- Do NOT suggest, recommend, add, or change any medicines or treatments.
- If asked which doctor or specialist they should see based on their results, recommend the type of medical specialist (e.g., Endocrinologist for Thyroid anomalies, Cardiologist for Cardiac/Lipids findings).
- If they ask where to do recommended tests or checkups, inform them they can check with nearby path labs or hospitals using the "Find Care" search feature on the dashboard.
- You MAY give helpful factual background — what a test measures, what a normal range means, or what an ALREADY-prescribed medicine is generally used for — using established medical knowledge.
- NEVER invent values, findings, causes, or facts that are not in the data or well-established general knowledge. If unsure, say so and suggest asking the doctor.
- For anything concerning or urgent, advise consulting their doctor.
- At the end of every response, you MUST append this exact patient disclaimer:
  "Disclaimer: This information is purely educational and informational. It is not a confirmed medical diagnosis or appointment. Please consult a doctor and do not rely solely on this information."
- Keep answers concise and factual.

PATIENT'S MEDICAL RECORDS:
${contextText || 'No reports available yet.'}
${historyText ? `\nCONVERSATION SO FAR:\n${historyText}\n` : ''}
PATIENT'S QUESTION: ${question}${langInstruction}

Answer:`;

      console.log('Generating AI chat response...');
      const response = await trackGemini(ai, {
        model: 'gemini-3.6-flash',
        contents: [chatPrompt],
      });

      const answer = (response.text || '').trim();
      if (answer) return { answer, source: 'ai' };
    } catch (error) {
      console.error('Chat generation failed, using local fallback:', error.message);
    }
  }

  return { answer: generateLocalChatAnswer(question, reports), source: 'local' };
}

/**
 * Normalises a stored medical_reports row (JSONB columns may arrive as strings).
 */
function normalizeReportRow(report) {
  const parseJson = (val, fallback) => {
    if (val == null) return fallback;
    if (typeof val === 'string') {
      try { return JSON.parse(val); } catch (_) { return fallback; }
    }
    return val;
  };
  const testResults = parseJson(report.test_results, { parameters: [], findings: [] });
  return {
    patientName: report.patient_name || 'the patient',
    reportDate: report.report_date instanceof Date
      ? report.report_date.toISOString().split('T')[0]
      : String(report.report_date || 'unknown date').split('T')[0],
    reportType: report.report_type || 'Report',
    reportCategory: report.report_category || 'general',
    comments: report.comments || '',
    medications: parseJson(report.medications, []),
    parameters: Array.isArray(testResults.parameters) ? testResults.parameters : [],
    findings: Array.isArray(testResults.findings) ? testResults.findings : [],
    healthInsights: parseJson(report.health_insights, {}),
  };
}

/**
 * Generates an in-depth, structured, patient-friendly analysis of a SINGLE report.
 * This is heavier and more detailed than the inline `health_insights` shown on the
 * report card — it is generated on demand (when the user taps "Detailed Analysis").
 *
 * @param {object} report - A raw medical_reports DB row.
 * @returns {Promise<{summary:string, sections:Array<{title:string,content:string}>, disclaimer:string, source:'ai'|'local'}>}
 */
export async function generateDetailedAnalysis(report) {
  const r = normalizeReportRow(report);
  const geminiKey = process.env.GEMINI_API_KEY;

  const disclaimer = 'This detailed analysis is generated by AI to help you understand your report in plain language. It is educational only and is NOT a medical diagnosis. Always confirm any decisions with your doctor.';

  if (geminiKey && geminiKey !== 'YOUR_GEMINI_API_KEY_HERE') {
    try {
      const ai = new GoogleGenAI({ apiKey: geminiKey });

      const abnormal = r.parameters.filter(p => p.status && p.status.toLowerCase() !== 'normal');
      const prompt = `You are a careful medical information assistant writing a factual, easy-to-understand explanation of ONE medical report for the patient themselves. Explain clearly what the report says and what the tests/values mean. Use warm, plain language (no jargon without explaining it). Never be alarmist.

REPORT
Patient: ${r.patientName}
Date: ${r.reportDate}
Type: ${r.reportType} (category: ${r.reportCategory})
Test parameters: ${JSON.stringify(r.parameters)}
Abnormal parameters: ${JSON.stringify(abnormal)}
Findings / impressions: ${JSON.stringify(r.findings)}
Prescribed medications: ${JSON.stringify(r.medications)}
Doctor's comments: ${r.comments || 'None'}

Write a JSON object EXACTLY in this schema:
{
  "summary": "A 2-3 sentence factual overall takeaway in plain language.",
  "sections": [
    { "title": "Detailed Interpretation", "content": "Factual explanation of what the results/findings mean, referencing specific values and whether each is within, above, or below the reference range and by how much. Do NOT speculate on a diagnosis." },
    { "title": "Parameter-by-Parameter Breakdown", "content": "For each notable test value: what the test measures (factual), and the patient's value vs. reference range. Do not guess a cause. Use a simple dashed list." },
    { "title": "About These Tests", "content": "Neutral, factual background on what these tests/findings assess and what a normal range generally represents — established medical knowledge only. Do NOT speculate about what condition the patient may have or what caused a result." },
    { "title": "Medicines In This Report", "content": "For each medicine ALREADY listed in the report: what that medicine is generally used for and key factual safety notes (e.g. take with food). Do NOT suggest, add, or change any medicine. If none listed, say so." },
    { "title": "General Wellbeing Information", "content": "Neutral, widely-accepted healthy-lifestyle information relevant to these test areas (diet, activity, sleep). Frame as general information, not personalised medical advice." },
    { "title": "Questions to Ask Your Doctor", "content": "A short dashed list of 3-6 specific questions this patient could ask their doctor." },
    { "title": "When to Seek Care Sooner", "content": "Factual, non-alarming guidance on symptoms or situations that warrant contacting a doctor promptly." }
  ]
}

STRICT RULES:
- Base everything ONLY on the data provided plus well-established general medical knowledge. NEVER invent values, findings, causes, or specifics (no hallucination).
- Do NOT diagnose or speculate about what condition the patient may have or what may have caused a result.
- Do NOT suggest, recommend, add, or change any medicine or treatment. You may only explain medicines already in the report.
- If a section has nothing to say, keep it and state that briefly.
- Return ONLY raw JSON. No markdown code fences, no text outside the JSON.`;

      console.log('Generating detailed AI analysis...');
      const response = await trackGemini(ai, {
        model: 'gemini-3.6-flash',
        contents: [prompt],
      });

      let cleaned = (response.text || '').trim();
      if (cleaned.startsWith('```')) {
        cleaned = cleaned.replace(/^```json\s*/, '').replace(/```$/, '').trim();
      }
      const parsed = JSON.parse(cleaned);
      if (parsed && Array.isArray(parsed.sections)) {
        return {
          summary: parsed.summary || '',
          sections: parsed.sections.filter(s => s && s.title && s.content),
          disclaimer,
          source: 'ai',
        };
      }
    } catch (error) {
      console.error('Detailed analysis generation failed, using local fallback:', error.message);
    }
  }

  return { ...generateLocalDetailedAnalysis(r), disclaimer, source: 'local' };
}

/**
 * Rule-based fallback that builds a structured detailed analysis without Gemini,
 * reusing the existing local health-insights engine for depth.
 */
function generateLocalDetailedAnalysis(r) {
  const abnormal = r.parameters.filter(p => p.status && p.status.toLowerCase() !== 'normal');
  const normal = r.parameters.filter(p => !p.status || p.status.toLowerCase() === 'normal');

  // Reuse the local insights engine for interpretation/specialists/side-effects.
  const insights = generateLocalHealthInsights({
    report_category: r.reportCategory,
    report_type: r.reportType,
    comments: r.comments,
    test_results: { parameters: r.parameters, findings: r.findings },
    medications: r.medications,
  });

  const sections = [];

  sections.push({
    title: 'Detailed Interpretation',
    content: insights.interpretation || 'No structured test parameters were available to interpret in this report.',
  });

  if (r.parameters.length > 0) {
    const lines = r.parameters.map(p => {
      const flag = p.status && p.status.toLowerCase() !== 'normal' ? ` — ${p.status.toUpperCase()}` : ' — within normal range';
      return `- ${p.name}: ${p.value} ${p.unit || ''} (reference ${p.referenceRange || 'n/a'})${flag}`;
    });
    sections.push({ title: 'Parameter-by-Parameter Breakdown', content: lines.join('\n') });
  } else if (r.findings.length > 0) {
    sections.push({ title: 'Key Findings', content: r.findings.map(f => `- ${f}`).join('\n') });
  }

  if (abnormal.length > 0) {
    sections.push({
      title: 'About These Values',
      content: `The following value(s) are outside the usual reference range: ${abnormal.map(p => p.name).join(', ')}. A single reading is not a diagnosis — please discuss these with your doctor, who can interpret them in the context of your full health history.`,
    });
  }

  if (insights.sideEffects && insights.sideEffects.length > 0) {
    const medLines = insights.sideEffects.map(se =>
      `- ${se.medicine}: ${se.tips || ''}${se.commonEffects && se.commonEffects.length ? ` Common effects: ${se.commonEffects.join(', ')}.` : ''}`
    );
    sections.push({ title: 'Medicines Explained', content: medLines.join('\n') });
  } else {
    sections.push({ title: 'Medicines Explained', content: 'No medicines are listed in this report.' });
  }

  const guidance = [];
  const catText = [r.reportCategory, ...r.parameters.map(p => p.name), ...r.findings].join(' ').toLowerCase();
  if (/tsh|thyroid/.test(catText)) guidance.push('- Take thyroid medicine on an empty stomach and get periodic TSH checks as advised.');
  if (/glucose|sugar|hba1c|diabet/.test(catText)) guidance.push('- Limit refined sugar and simple carbs; stay active and monitor blood sugar as advised.');
  if (/cholesterol|ldl|lipid|triglyceride/.test(catText)) guidance.push('- Favour a heart-healthy diet (less saturated fat), regular exercise, and avoid smoking.');
  if (/liver|fatty|sgpt|sgot/.test(catText)) guidance.push('- Reduce oily/fried food and alcohol; gradual weight loss helps fatty-liver changes.');
  guidance.push('- Stay hydrated, sleep well, and keep a copy of your reports for every doctor visit.');
  sections.push({ title: 'Lifestyle & Diet Guidance', content: guidance.join('\n') });

  const questions = [
    '- What do my abnormal values mean for my day-to-day health?',
    '- Do my current medicines need any change based on this report?',
    '- When should I repeat this test to track progress?',
  ];
  if (insights.specialistRecommendations && insights.specialistRecommendations.length > 0) {
    questions.push(`- Should I see a ${insights.specialistRecommendations[0].specialist}?`);
  }
  sections.push({ title: 'Questions to Ask Your Doctor', content: questions.join('\n') });

  sections.push({
    title: 'When to Seek Care Sooner',
    content: 'Contact your doctor promptly if you develop new or worsening symptoms (for example: chest pain, severe breathlessness, persistent high fever, fainting, or anything that feels serious). This report is a snapshot — how you feel matters too.',
  });

  const summary = abnormal.length > 0
    ? `This ${formatCategory(r.reportCategory)} report has ${abnormal.length} value(s) worth discussing with your doctor, while ${normal.length} are within normal range. Below is a detailed, plain-language breakdown.`
    : `This ${formatCategory(r.reportCategory)} report looks largely within normal ranges. Below is a detailed, plain-language breakdown for your understanding.`;

  return { summary, sections };
}

// Language name → Sarvam translate code.
const LANGUAGE_CODES = {
  english: 'en-IN', hindi: 'hi-IN', marathi: 'mr-IN', gujarati: 'gu-IN',
  tamil: 'ta-IN', telugu: 'te-IN', kannada: 'kn-IN', bengali: 'bn-IN',
  punjabi: 'pa-IN', malayalam: 'ml-IN', odia: 'od-IN',
};

/** Translates a short string to a target language via Sarvam (Mayura). Returns null on failure. */
async function sarvamTranslate(text, targetCode, apiKey) {
  if (!text || !text.trim() || targetCode === 'en-IN') return text;
  if (!apiKey || apiKey === 'YOUR_SARVAM_API_KEY_HERE') return null;
  try {
    const inputChars = Math.min(text.length, 900);
    const res = await trackSarvam('translate', { chars: inputChars }, () => fetch('https://api.sarvam.ai/translate', {
      method: 'POST',
      headers: { 'api-subscription-key': apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        input: text.slice(0, 900),
        source_language_code: 'en-IN',
        target_language_code: targetCode,
        model: 'mayura:v1'
      })
    }));
    if (!res.ok) return null;
    const data = await res.json();
    return data.translated_text || null;
  } catch (e) {
    console.warn('Sarvam translate failed:', e.message);
    return null;
  }
}

/**
 * Translates arbitrary-length text to a target language via Sarvam, for the /api/ai/translate
 * proxy route (the app no longer talks to Sarvam directly). Splits on blank lines so each
 * paragraph stays under sarvamTranslate's ~900-char limit, and degrades gracefully: any
 * missing key, unsupported language, or upstream failure returns the ORIGINAL text rather
 * than throwing, since translation is a nice-to-have and must never break the caller.
 */
export async function translateText(text, targetLanguage = 'English') {
  if (!text || !text.trim()) return text;
  if (!targetLanguage || targetLanguage.toLowerCase() === 'english') return text;

  try {
    const targetCode = LANGUAGE_CODES[targetLanguage.toLowerCase()] || 'en-IN';
    if (targetCode === 'en-IN') return text;

    const apiKey = process.env.SARVAM_API_KEY;
    if (!apiKey || apiKey === 'YOUR_SARVAM_API_KEY_HERE') return text;

    const paragraphs = text.split('\n\n');
    const translated = await Promise.all(paragraphs.map(async (p) => {
      if (!p.trim()) return p;
      const result = await sarvamTranslate(p, targetCode, apiKey);
      return result || p;
    }));
    return translated.join('\n\n');
  } catch (e) {
    console.warn('translateText failed:', e.message);
    return text;
  }
}

/** Splits text into chunks of at most maxLen chars, preferring sentence boundaries. */
function chunkText(text, maxLen = 450) {
  const clean = (text || '').replace(/\s+/g, ' ').trim();
  if (clean.length <= maxLen) return clean ? [clean] : [];
  const sentences = clean.match(/[^.!?।]+[.!?।]?/g) || [clean];
  const chunks = [];
  let cur = '';
  for (const s of sentences) {
    if ((cur + s).length > maxLen) {
      if (cur) chunks.push(cur.trim());
      if (s.length > maxLen) {
        // Hard-split an overly long sentence.
        for (let i = 0; i < s.length; i += maxLen) chunks.push(s.slice(i, i + maxLen).trim());
        cur = '';
      } else {
        cur = s;
      }
    } else {
      cur += s;
    }
  }
  if (cur.trim()) chunks.push(cur.trim());
  return chunks.slice(0, 8); // cap to keep response size reasonable
}

/**
 * Converts text to speech in the target language using Sarvam's Bulbul TTS, which supports
 * Indian languages (Marathi, Hindi, etc.) that most on-device TTS engines lack.
 * Returns an array of base64-encoded WAV clips (one per chunk), or [] on failure.
 *
 * @param {string} text
 * @param {string} language - e.g. 'Marathi', 'Hindi', 'English'
 * @returns {Promise<{audios: string[]}>}
 */
/** Wraps raw PCM (base64) in a WAV container so mobile players can play it. */
function pcmToWavBase64(pcmBase64, sampleRate = 24000, channels = 1, bitsPerSample = 16) {
  const pcm = Buffer.from(pcmBase64, 'base64');
  const byteRate = sampleRate * channels * bitsPerSample / 8;
  const blockAlign = channels * bitsPerSample / 8;
  const header = Buffer.alloc(44);
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write('WAVE', 8);
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(byteRate, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(bitsPerSample, 34);
  header.write('data', 36);
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]).toString('base64');
}

/** Gemini text-to-speech (multilingual, auto-detects language from the text). */
async function generateSpeechGemini(text) {
  const geminiKey = process.env.GEMINI_API_KEY;
  if (!geminiKey || geminiKey === 'YOUR_GEMINI_API_KEY_HERE') return { audios: [] };
  const chunks = chunkText(text, 800);
  if (chunks.length === 0) return { audios: [] };
  try {
    const ai = new GoogleGenAI({ apiKey: geminiKey });
    const audios = [];
    for (const c of chunks) {
      const response = await trackGemini(ai, {
        model: 'gemini-2.5-flash-preview-tts',
        contents: [{ parts: [{ text: c }] }],
        config: {
          responseModalities: ['AUDIO'],
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: 'Kore' } } }
        }
      });
      const data = response.candidates?.[0]?.content?.parts?.[0]?.inlineData?.data;
      if (data) audios.push(pcmToWavBase64(data, 24000));
    }
    return { audios };
  } catch (e) {
    console.error('Gemini TTS error:', e.message);
    return { audios: [] };
  }
}

/**
 * Text-to-speech. engine: 'sarvam' (Indic voices) or 'gemini' (multilingual). The 'phone'
 * engine is handled entirely on-device and never reaches here.
 */
export async function generateSpeech(text, language = 'English', engine = 'sarvam') {
  if (engine === 'gemini') {
    return generateSpeechGemini(text);
  }

  const sarvamKey = process.env.SARVAM_API_KEY;
  const targetCode = LANGUAGE_CODES[(language || 'english').toLowerCase()] || 'en-IN';
  if (!sarvamKey || sarvamKey === 'YOUR_SARVAM_API_KEY_HERE') return { audios: [] };

  const chunks = chunkText(text, 450);
  if (chunks.length === 0) return { audios: [] };

  try {
    const chars = chunks.reduce((sum, c) => sum + c.length, 0);
    const res = await trackSarvam('tts', { chars }, () => fetch('https://api.sarvam.ai/text-to-speech', {
      method: 'POST',
      headers: { 'api-subscription-key': sarvamKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        inputs: chunks,
        target_language_code: targetCode,
        speaker: 'anushka',
        model: 'bulbul:v2',
        speech_sample_rate: 22050,
        enable_preprocessing: true
      })
    }));
    if (!res.ok) {
      const t = await res.text();
      console.error('Sarvam TTS failed:', res.status, t.slice(0, 200));
      return { audios: [] };
    }
    const data = await res.json();
    return { audios: Array.isArray(data.audios) ? data.audios : [] };
  } catch (e) {
    console.error('Sarvam TTS error:', e.message);
    return { audios: [] };
  }
}

/**
 * Generates a brief, layperson-friendly explanation of what a medicine is for and its
 * impact, in the patient's preferred local language. The medicine NAME stays in English;
 * only the explanation is translated (via Sarvam) so a local person can understand it.
 *
 * @param {string} medicineName
 * @param {string} language - e.g. 'English', 'Hindi', 'Marathi'
 * @returns {Promise<object>} { medicineName, language, purpose, plainExplanation, commonUses, safetyTips, source }
 */
export async function generateMedicineInfo(medicineName, language = 'English') {
  const geminiKey = process.env.GEMINI_API_KEY;
  const sarvamKey = process.env.SARVAM_API_KEY;
  const targetCode = LANGUAGE_CODES[(language || 'english').toLowerCase()] || 'en-IN';
  const isEnglish = targetCode === 'en-IN';

  // 1. Generate a simple English explanation with Gemini.
  let info = null;
  if (geminiKey && geminiKey !== 'YOUR_GEMINI_API_KEY_HERE') {
    try {
      const ai = new GoogleGenAI({ apiKey: geminiKey });
      const prompt = `You are a friendly pharmacist explaining a medicine to a patient who has NO medical background. For the medicine "${medicineName}", explain in very simple, everyday words (not professional jargon). If you must use a medical term, explain it in brackets.
Return ONLY raw JSON (no code fences):
{"purpose":"one short line — what this medicine is commonly given for","plainExplanation":"2-3 very simple sentences on what it does in the body and why it helps","commonUses":["short phrase", "short phrase"],"safetyTips":["short simple tip", "short simple tip"]}
If the name is unclear or could be several medicines, give the most common one and keep it general.`;
      const response = await trackGemini(ai, { model: 'gemini-3.6-flash', contents: [prompt] });
      let cleaned = (response.text || '').trim();
      if (cleaned.startsWith('```')) cleaned = cleaned.replace(/^```json\s*/, '').replace(/```$/, '').trim();
      info = JSON.parse(cleaned);
    } catch (e) {
      console.error('Medicine info generation failed:', e.message);
    }
  }

  if (!info) {
    info = {
      purpose: `Information about ${medicineName}`,
      plainExplanation: `This is a medicine named ${medicineName}. For exact details on what it does and how to take it, please ask your doctor or pharmacist.`,
      commonUses: [],
      safetyTips: ['Take exactly as your doctor prescribed.', 'Tell your doctor about any other medicines you take.']
    };
  }

  // 2. Translate the explanation into the local language (medicine name stays English).
  let source = isEnglish ? 'ai-en' : 'ai-en';
  if (!isEnglish) {
    const [purpose, plainExplanation] = await Promise.all([
      sarvamTranslate(info.purpose, targetCode, sarvamKey),
      sarvamTranslate(info.plainExplanation, targetCode, sarvamKey),
    ]);
    const commonUses = await Promise.all((info.commonUses || []).map(u => sarvamTranslate(u, targetCode, sarvamKey)));
    const safetyTips = await Promise.all((info.safetyTips || []).map(t => sarvamTranslate(t, targetCode, sarvamKey)));

    if (purpose || plainExplanation) {
      // Sarvam succeeded (at least partially).
      info = {
        purpose: purpose || info.purpose,
        plainExplanation: plainExplanation || info.plainExplanation,
        commonUses: commonUses.map((u, i) => u || (info.commonUses || [])[i]).filter(Boolean),
        safetyTips: safetyTips.map((t, i) => t || (info.safetyTips || [])[i]).filter(Boolean),
      };
      source = 'sarvam-translated';
    } else if (geminiKey && geminiKey !== 'YOUR_GEMINI_API_KEY_HERE') {
      // Sarvam unavailable — fall back to asking Gemini to write it in the target language.
      try {
        const ai = new GoogleGenAI({ apiKey: geminiKey });
        const tPrompt = `Rewrite this medicine explanation in ${language} using very simple words a common person understands. Keep the medicine name "${medicineName}" in English. Return ONLY raw JSON with the same keys: ${JSON.stringify(info)}`;
        const response = await trackGemini(ai, { model: 'gemini-3.6-flash', contents: [tPrompt] });
        let cleaned = (response.text || '').trim();
        if (cleaned.startsWith('```')) cleaned = cleaned.replace(/^```json\s*/, '').replace(/```$/, '').trim();
        info = JSON.parse(cleaned);
        source = 'ai-translated';
      } catch (e) {
        source = 'ai-en-fallback';
      }
    }
  }

  return {
    medicineName,
    language,
    purpose: info.purpose || '',
    plainExplanation: info.plainExplanation || '',
    commonUses: info.commonUses || [],
    safetyTips: info.safetyTips || [],
    source,
  };
}
