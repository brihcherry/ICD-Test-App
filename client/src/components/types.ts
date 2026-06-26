export type TableCandidate = {
	index: number;
	displayLabel: string;
	firstRowPreview: string[];
	rowCount: number;
	previewRows: string[][];
	columnCount: number;
};

export type GetTablesResponse = {
	tableCount: number;
	tableCandidates: TableCandidate[];
};

export type SendToModelBatch = {
	batchNumber: number;
	batchSize: number;
	prompt: string;
	llmCommand?: string;
};

export type SendToModelResponse = {
	totalBatches: number;
	batches: SendToModelBatch[];
};

export type ModelResultRow = {
	id: string;
	dataElement: string;
	description: string;
	variableType: string;
	fieldLength: string;
	delimited: string;
	valueRange: string;
	dataSubjectArea: string;
	dataSubjectAreaAlt1: string;
	dataSubjectAreaAlt2: string;
	confidence: string;
};

export type DeletedResultRowEntry = {
	row: ModelResultRow;
	index: number;
	deleteToken: number;
	deletedAt: string;
};

export type ModelBatchOutput = {
	batchNumber: number;
	rawOutput: string;
	parsedRowCount: number;
	warnings: string[];
};

export type DownloadJsonPayload = {
	fileName: string;
	mimeType: string;
	fileContentBase64: string;
	byteCount: number;
};
