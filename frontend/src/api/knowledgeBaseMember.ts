import axios from './axios';

export const knowledgeBaseMemberApi = {
  join: (params: any) => axios.post('/ai/api/knowledgeBaseMember/join', params),
  leave: (params: any) => axios.post('/ai/api/knowledgeBaseMember/leave', params),
  remove: (params: any) => axios.post('/ai/api/knowledgeBaseMember/remove', params),
  members: (params: any) => axios.post('/ai/api/knowledgeBaseMember/members', params),
  joinedIds: (params: any) => axios.post('/ai/api/knowledgeBaseMember/joinedIds', params),
};
