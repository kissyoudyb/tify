import { get, post, put, del } from '@/utils/request'

export interface WorkflowListItem {
  id: number
  name: string
  description: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface WorkflowDetail {
  id: number
  name: string
  description: string
  status: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
  createdAt: string
  updatedAt: string
}

export interface WorkflowNode {
  nodeKey: string
  type: string
  name: string
  config: Record<string, any>
}

export interface WorkflowEdge {
  sourceNodeKey: string
  targetNodeKey: string
  condition: string | null
}

export interface WorkflowCreateRequest {
  name: string
  description?: string
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}

export interface WorkflowUpdateRequest {
  name: string
  description?: string
  status?: string
  nodes?: WorkflowNode[]
  edges?: WorkflowEdge[]
}

export interface WorkflowRun {
  id: number
  workflowId: number
  status: string
  input: string | null
  output: string | null
  error: string | null
  elapsedMs: number | null
  createdAt: string
  finishedAt: string | null
}

export interface WorkflowNodeRun {
  id: number
  workflowRunId: number
  nodeKey: string
  nodeType: string
  status: string
  outputs: Record<string, any> | null
  error: string | null
  elapsedMs: number | null
  createdAt: string
  finishedAt: string | null
}

export interface WorkflowRunDetail {
  run: WorkflowRun
  nodeRuns: WorkflowNodeRun[]
}

export function listWorkflows(params?: { page?: number; pageSize?: number; status?: string }) {
  return get<any>('/v1/workflows', { page: 1, pageSize: 20, ...params })
}

export function getWorkflow(id: number) {
  return get<any>(`/v1/workflows/${id}`)
}

export function createWorkflow(data: WorkflowCreateRequest) {
  return post<any>('/v1/workflows', data)
}

export function updateWorkflow(id: number, data: WorkflowUpdateRequest) {
  return put<any>(`/v1/workflows/${id}`, data)
}

export function deleteWorkflow(id: number) {
  return del<any>(`/v1/workflows/${id}`)
}

export function listWorkflowRuns(id: number, params?: { page?: number; pageSize?: number }) {
  return get<any>(`/v1/workflows/${id}/runs`, { page: 1, pageSize: 10, ...params })
}

export function getWorkflowRun(id: number, runId: number) {
  return get<any>(`/v1/workflows/${id}/runs/${runId}`)
}

export function getLatestWorkflowRun(id: number) {
  return get<any>(`/v1/workflows/${id}/runs/latest`)
}
